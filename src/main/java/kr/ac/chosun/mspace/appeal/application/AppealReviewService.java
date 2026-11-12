package kr.ac.chosun.mspace.appeal.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
            // 한 요청이 실패해도(판단기 오류, 이상한 확신도, DB 오류) 다음 요청은 계속 본다. 실패한 요청은 대기로 남아 다음 번에 다시
            try {
                if (reviewOne(id)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("철회 요청 판단 실패, 다음 번에 다시 봄: appeal={} error={}", id, e.getClass().getSimpleName());
            }
        }
        return done;
    }

    private boolean reviewOne(long id) {
        Optional<WarningReviewer.Case> c = port.loadCase(id);
        if (c.isEmpty()) {
            return false;
        }
        // 관리자가 이미 철회한 경고면 판단기를 부르지 않고 닫는다. 판단기가 실패해도 대기로 남아 계속 다시 부르지 않게 (CodeRabbit #123)
        if (Boolean.TRUE.equals(tx.execute(st -> closeIfWithdrawn(id)))) {
            return true;
        }
        Review review = reviewer.review(c.get());
        if (review == null || review.decision() == null || !(review.confidence() >= 0 && review.confidence() <= 1)) {
            throw new IllegalStateException("판단기 결과가 올바르지 않다 (결정 없음 또는 확신도 0~1 밖)");
        }
        // apply가 Optional을 돌려줘 tx.execute 결과가 null일 일이 없다 (Sonar S2583, #116 영준 제안)
        Optional<List<Notification>> stored = Objects.requireNonNull(tx.execute(st -> apply(id, review)));
        if (stored.isEmpty()) {
            return false;
        }
        inbox.publish(stored.get());
        return true;
    }

    static final String ALREADY_WITHDRAWN = "이미 철회된 경고예요.";

    /** 판단기를 부르기 전: 요청 → 사람 순으로 잠그고 경고가 이미 철회됐으면 닫는다. 잠금 순서는 apply와 같다 */
    private boolean closeIfWithdrawn(long appealId) {
        Optional<WarningAppealPort.Pending> locked = port.lockPending(appealId);
        if (locked.isEmpty()) {
            return false;
        }
        penalties.lockUser(locked.get().userId());
        return closeWithdrawn(appealId, locked.get(), clock.instant());
    }

    private boolean closeWithdrawn(long appealId, WarningAppealPort.Pending p, Instant now) {
        if (!port.isWithdrawn(p.warningId())) {
            return false;
        }
        port.decide(appealId, Decision.WITHDRAWN, ALREADY_WITHDRAWN,
                new Review(Decision.WITHDRAWN, 1, List.of(ALREADY_WITHDRAWN), List.of(), "withdrawn", "withdrawn-v1"), now);
        return true;
    }

    /** @return 알림함에 넣은 알림. 다른 판단이 먼저 끝냈으면 빈 Optional */
    private Optional<List<Notification>> apply(long appealId, Review review) {
        Optional<WarningAppealPort.Pending> locked = port.lockPending(appealId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        WarningAppealPort.Pending p = locked.get();
        Instant now = clock.instant();
        boolean unsure = review.decision() == Decision.KEPT && review.confidence() < policy.minConfidence();
        Decision decision = unsure ? Decision.WITHDRAWN : review.decision();
        String answer = answer(review, unsure);

        penalties.lockUser(p.userId());
        // 판단하는 사이 관리자가 먼저 철회했으면(관리자 철회도 같은 사람을 잠근다) 판단 결과와 상관없이 철회로 닫고
        // 알림은 보내지 않는다. 관리자 철회가 이미 알렸다 (재웅 #123)
        if (closeWithdrawn(appealId, p, now)) {
            return Optional.of(List.of());
        }
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
        return Optional.of(inbox.store(out, now));
    }

    /** 알림 본문은 300자까지 (V10). 자세한 이유는 내 경고 목록의 answer에 다 있다 */
    static String clip(String body) {
        // DB varchar는 글자(코드 포인트)로 센다. 이모지가 반으로 잘리지 않게 코드 포인트로 자른다 (CodeRabbit #123)
        return body.codePointCount(0, body.length()) <= BODY_MAX ? body
                : body.substring(0, body.offsetByCodePoints(0, BODY_MAX - 1)) + "…";
    }

    private static String answer(Review review, boolean unsure) {
        // 판단기가 "유지"라도 확신이 낮으면 그 이유 대신 원칙을 알린다 (유지 이유를 붙이면 결과와 어긋나 보임)
        String text = unsure
                ? "근거를 충분히 확인할 수 없었어요. 확인할 수 없으면 벌칙을 주지 않아요."
                : String.join(" ", review.reasons()).strip();
        return text.length() > 1000 ? text.substring(0, 999) + "…" : text;
    }
}
