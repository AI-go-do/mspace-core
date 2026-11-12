package kr.ac.chosun.mspace.appeal.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kr.ac.chosun.mspace.appeal.application.AdminWithdrawPort.Warning;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Decision;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Review;
import kr.ac.chosun.mspace.common.config.ReportPenaltyPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.report.application.ReportPenaltyPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관리자 경고 철회. 철회 요청 판단(AppealReviewService)과 같은 순서로 잠근다: 대기 중 철회 요청 → 사람(경고·정지 집계) → 경고.
 * 그래서 AI 판단과 관리자 철회가 동시에 와도 서로 기다릴 뿐 엇갈려 막히지 않고, 먼저 끝낸 쪽만 반영된다
 */
@Service
class AdminWithdrawService implements AdminWithdrawUseCase {

    static final String REVIEWER = "admin";
    static final String VERSION = "admin-v1";

    private final AdminWithdrawPort admin;
    private final WarningAppealPort appeals;
    private final ReportPenaltyPort penalties;
    private final ReportPenaltyPolicy penaltyPolicy;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;
    private final Clock clock;

    AdminWithdrawService(AdminWithdrawPort admin, WarningAppealPort appeals, ReportPenaltyPort penalties,
                         ReportPenaltyPolicy penaltyPolicy, NotificationInbox inbox, TransactionOperations tx,
                         Clock clock) {
        this.admin = admin;
        this.appeals = appeals;
        this.penalties = penalties;
        this.penaltyPolicy = penaltyPolicy;
        this.inbox = inbox;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Withdrawn withdraw(long adminId, long warningId, String reason) {
        if (!admin.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.codePointCount(0, why.length()) > REASON_MAX) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 이유는 1–" + REASON_MAX + "자로 적어 주세요");
        }
        Result r = Objects.requireNonNull(tx.execute(st -> apply(adminId, warningId, why)));
        inbox.publish(r.stored());
        return r.withdrawn();
    }

    private record Result(Withdrawn withdrawn, List<Notification> stored) {
    }

    private Result apply(long adminId, long warningId, String why) {
        Optional<WarningAppealPort.Pending> pending = admin.pendingAppealOf(warningId).flatMap(appeals::lockPending);
        Warning w = admin.find(warningId).orElseThrow(() -> new BusinessException(ErrorCode.WARNING_NOT_FOUND));
        penalties.lockUser(w.userId());
        Instant now = clock.instant();
        if (w.withdrawnAt() != null || !admin.withdrawByAdmin(warningId, adminId, why, now)) {
            throw new BusinessException(ErrorCode.WARNING_ALREADY_WITHDRAWN);
        }
        boolean lifted = appeals.liftSuspension(w.userId(), warningId, w.reason(), penaltyPolicy.windowDays(), now);
        String answer = "관리자가 다시 확인하고 경고를 철회했어요. " + why;
        if (pending.isPresent()) {
            Review byAdmin = new Review(Decision.WITHDRAWN, 1, List.of(why), List.of(), REVIEWER, VERSION);
            appeals.decide(pending.get().appealId(), Decision.WITHDRAWN, clip(answer, 1000), byAdmin, now);
        }
        String body = answer + (lifted ? " 이 경고로 걸린 정지도 지금 풀었어요." : "");
        List<Notification> stored = inbox.store(List.of(new NewNotification(w.userId(), w.reservationId(),
                AppealReviewService.KIND, "경고가 철회됐어요", AppealReviewService.clip(body))), now);
        return new Result(new Withdrawn(warningId, w.userId(), now, lifted, pending.isPresent()), stored);
    }

    private static String clip(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
    }
}
