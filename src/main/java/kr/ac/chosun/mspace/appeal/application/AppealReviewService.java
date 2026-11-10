package kr.ac.chosun.mspace.appeal.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Decision;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Review;
import kr.ac.chosun.mspace.common.config.AppealPolicy;
import kr.ac.chosun.mspace.common.config.ReportPenaltyPolicy;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.report.application.ReportPenaltyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 대기 중인 철회 요청을 판단기(WarningReviewer)에 넘기고 결과를 적용한다 (FR-RPT-105, Q-24 11/10 지영 "추천대로").
 * <ol>
 *   <li>판단기 호출은 트랜잭션 밖에서 한다 (AI가 느려도 DB 잠금을 쥐고 있지 않게)</li>
 *   <li>결과 적용은 한 트랜잭션: 요청이 아직 대기 중인지 잠가 보고, 같은 사람 벌칙 잠금 아래에서
 *       경고 철회 → 그 경고가 건 정지 풀기(②) → 판단 기록 → 알림함</li>
 *   <li>판단기가 "유지"라도 확신도가 기준보다 낮으면 철회한다 (③ 확인 불가 = 철회). 기록에는 판단기가 낸 원래 확신도를 남긴다</li>
 *   <li>판단기가 실패하면 그 요청은 대기로 두고 다음 번에 다시 본다</li>
 * </ol>
 */
@Service
public class AppealReviewService implements AppealReviewUseCase {

    private static final Logger log = LoggerFactory.getLogger(AppealReviewService.class);
    static final String KIND = "WARNING_APPEAL_RESULT";
    private static final int BODY_MAX = 300;

    private final WarningAppealPort port;
    private final WarningReviewer reviewer;
    private final ReportPenaltyPort penalties;
    private final AppealPolicy policy;
    private final ReportPenaltyPolicy penaltyPolicy;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;
    private final Clock clock;

    public AppealReviewService(WarningAppealPort port, WarningReviewer reviewer, ReportPenaltyPort penalties,
                               AppealPolicy policy, ReportPenaltyPolicy penaltyPolicy, NotificationInbox inbox,
                               TransactionOperations tx, Clock clock) {
        this.port = port;
        this.reviewer = reviewer;
        this.penalties = penalties;
        this.policy = policy;
        this.penaltyPolicy = penaltyPolicy;
        this.inbox = inbox;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public int reviewPending() {
        int done = 0;
        for (long id : port.pending(policy.batchSize())) {
            Optional<WarningReviewer.Case> c = port.loadCase(id);
            if (c.isEmpty()) {
                continue;
            }
            Review review;
            try {
                review = reviewer.review(c.get());
            } catch (RuntimeException e) {
                log.warn("철회 요청 판단 실패, 다음 번에 다시 봄: appeal={} error={}", id, e.getClass().getSimpleName());
                continue;
            }
            List<Notification> stored = tx.execute(st -> apply(id, review));
            if (stored != null) {
                inbox.publish(stored);
                done++;
            }
        }
        return done;
    }

    /** @return 알림함에 넣은 알림. 다른 판단이 먼저 끝냈으면 null */
    private List<Notification> apply(long appealId, Review review) {
        Optional<WarningAppealPort.Pending> locked = port.lockPending(appealId);
        if (locked.isEmpty()) {
            return null;
        }
        WarningAppealPort.Pending p = locked.get();
        Instant now = clock.instant();
        boolean unsure = review.decision() == Decision.KEPT && review.confidence() < policy.minConfidence();
        Decision decision = unsure ? Decision.WITHDRAWN : review.decision();
        String answer = answer(review, unsure);

        penalties.lockUser(p.userId());
        boolean lifted = false;
        if (decision == Decision.WITHDRAWN) {
            port.withdraw(p.warningId(), now);
            lifted = port.liftSuspension(p.userId(), p.warningId(), p.reason(), penaltyPolicy.windowDays(), now);
        }
        port.decide(appealId, decision, answer, review, now);

        List<NewNotification> out = new ArrayList<>();
        if (decision == Decision.WITHDRAWN) {
            String body = "다시 확인해 보니 경고를 철회했어요. " + answer
                    + (lifted ? " 이 경고로 걸린 정지도 지금 풀었어요." : "");
            out.add(new NewNotification(p.userId(), p.reservationId(), KIND, "경고가 철회됐어요", clip(body)));
        } else {
            out.add(new NewNotification(p.userId(), p.reservationId(), KIND, "경고가 유지됐어요",
                    clip("다시 확인했지만 경고는 그대로예요. " + answer)));
        }
        return inbox.store(out, now);
    }

    /** 알림 본문은 300자까지 (V10). 자세한 이유는 내 경고 목록의 answer에 다 있다 */
    static String clip(String body) {
        return body.length() <= BODY_MAX ? body : body.substring(0, BODY_MAX - 1) + "…";
    }

    private static String answer(Review review, boolean unsure) {
        // 판단기가 "유지"라도 확신이 낮으면 그 이유 대신 원칙을 알린다 (유지 이유를 붙이면 결과와 어긋나 보임)
        String text = unsure
                ? "근거를 충분히 확인할 수 없었어요. 확인할 수 없으면 벌칙을 주지 않아요."
                : String.join(" ", review.reasons()).strip();
        return text.length() > 1000 ? text.substring(0, 999) + "…" : text;
    }
}
