package kr.ac.chosun.mspace.report.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.common.config.ReportPenaltyPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.report.application.ReportPenaltyPort.WarningReason;
import kr.ac.chosun.mspace.report.domain.ReportCategory;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관리자 신고 판정 (FR-RPT-103)과 판정 뒤 벌칙 (FR-RPT-104, 11/6 지영 "docs 제안대로").
 * <ul>
 *   <li>관리자가 아니면 403 (예약 건수 API와 같은 ADMIN_ONLY)</li>
 *   <li>소음은 판정 대상이 아니라 409 (증명할 수 없어서. DB noise_not_false도 허위를 막는다)</li>
 *   <li>판정은 한 번만. 대기(OPEN)가 아니면 409. 두 관리자가 동시에 눌러도 조건부 UPDATE라 한 명만 된다</li>
 *   <li>사실: 신고 직전에 그 방을 쓴 예약의 대표자에게 경고. 30일 안 3번이면 14일 이용 정지</li>
 *   <li>허위: 신고자에게 경고. 30일 안 3번이면 14일 신고 정지 (예약은 된다)</li>
 *   <li>확인 불가: 벌칙 없음</li>
 *   <li>신고자에게는 판정 결과를, 경고 받은 사람에게는 경고(와 정지)를 알림함으로 보낸다</li>
 * </ul>
 * 판정·경고·정지·알림함 저장은 한 트랜잭션이라 같이 되거나 같이 안 된다. 메일 등 바깥 채널은 커밋 뒤에 보낸다.
 * 숫자는 mspace.report.penalty.
 */
@Service
public class JudgeReportService implements JudgeReportUseCase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private final ReportJudgePort reports;
    private final ReportPenaltyPort penalties;
    private final ReportPenaltyPolicy policy;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;
    private final Clock clock;

    public JudgeReportService(ReportJudgePort reports, ReportPenaltyPort penalties, ReportPenaltyPolicy policy,
                              NotificationInbox inbox, TransactionOperations tx, Clock clock) {
        this.reports = reports;
        this.penalties = penalties;
        this.policy = policy;
        this.inbox = inbox;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public List<ReportItem> list(long adminId, ReportVerdict verdict) {
        requireAdmin(adminId);
        return reports.list(verdict);
    }

    private record Judged(ReportItem report, List<Notification> notifications) {
    }

    @Override
    public ReportItem judge(long adminId, long reportId, ReportVerdict verdict) {
        requireAdmin(adminId);
        if (verdict == null || verdict == ReportVerdict.OPEN) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "판정(verdict)은 CONFIRMED, UNVERIFIED, FALSE 중 하나입니다");
        }
        Judged judged = tx.execute(status -> {
            ReportItem report = reports.judgeIfOpen(reportId, adminId, verdict).orElseThrow(() -> notJudgeable(reportId));
            Instant now = clock.instant();
            return new Judged(report, inbox.store(penalize(report, now), now));
        });
        inbox.publish(judged.notifications());
        return judged.report();
    }

    private BusinessException notJudgeable(long reportId) {
        ReportItem report = reports.find(reportId).orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        return report.category() == ReportCategory.NOISE
                ? new BusinessException(ErrorCode.REPORT_NOT_JUDGEABLE)
                : new BusinessException(ErrorCode.REPORT_ALREADY_JUDGED);
    }

    /** 경고·정지를 남기고, 보낼 알림을 돌려준다 */
    private List<NewNotification> penalize(ReportItem report, Instant now) {
        List<NewNotification> out = new ArrayList<>();
        // 카메라 자동 신고(FR-DEV-105)는 신고자가 없다: 결과 알림도, 허위 경고도 없다
        Optional<Long> reporter = penalties.reporterId(report.id());
        String what = label(report.category()) + " 신고";
        switch (report.verdict()) {
            case CONFIRMED -> {
                reporter.ifPresent(r -> out.add(new NewNotification(r, null, "REPORT_RESULT", "신고 결과가 나왔어요",
                        what + "가 사실로 확인됐어요. 알려 줘서 고마워요.")));
                penalties.findViolator(report.id()).ifPresent(v -> {
                    Penalty p = warn(v.userId(), WarningReason.VIOLATION, report.id(), v.reservationId(), now);
                    out.add(new NewNotification(v.userId(), v.reservationId(), "WARNING",
                            p.suspendedUntil == null ? "경고를 받았어요" : "이용이 정지됐어요",
                            "이용하신 방의 " + what + "가 사실로 확인됐어요. " + p.describe("예약·참여")));
                });
            }
            case UNVERIFIED -> reporter.ifPresent(r -> out.add(new NewNotification(r, null, "REPORT_RESULT",
                    "신고 결과가 나왔어요", what + "는 확인할 수 없어 처리하지 않았어요. 벌칙은 없어요.")));
            case FALSE -> reporter.ifPresent(r -> {
                Penalty p = warn(r, WarningReason.FALSE_REPORT, report.id(), null, now);
                out.add(new NewNotification(r, null, "REPORT_RESULT",
                        p.suspendedUntil == null ? "신고가 허위로 판정됐어요" : "신고가 정지됐어요",
                        what + "가 허위로 판정됐어요. " + p.describe("신고")));
            });
            case OPEN -> throw new IllegalStateException("판정 뒤에 OPEN일 수 없다");
        }
        return out;
    }

    /** @param suspendedUntil 이번 경고로 정지가 걸렸으면 끝나는 시각, 아니면 null */
    private record Penalty(int warnings, int limit, int windowDays, int suspendDays, Instant suspendedUntil) {
        String describe(String blocked) {
            if (suspendedUntil != null) {
                return "%d일 안 경고 %d회라 %s까지 %s할 수 없어요."
                        .formatted(windowDays, warnings, UNTIL.format(suspendedUntil.atZone(SEOUL)), blocked);
            }
            if (warnings >= limit) { // 이미 다른 정지(노쇼 정지 등)와 기간이 겹쳐 새 정지를 걸지 않은 경우
                return "%d일 안 경고 %d회예요. 지금 정지 기간과 겹쳐 정지가 더 걸리지는 않아요."
                        .formatted(windowDays, warnings);
            }
            return "%d일 안 경고 %d회째예요. %d회가 되면 %d일 동안 %s할 수 없어요."
                    .formatted(windowDays, warnings, limit, suspendDays, blocked);
        }
    }

    private Penalty warn(long userId, WarningReason reason, long reportId, Long reservationId, Instant now) {
        penalties.lockUser(userId);
        long warningId = penalties.warn(userId, reason, reportId, reservationId, now);
        int warnings = penalties.countWarnings(userId, reason, now, policy.windowDays());
        Instant until = null;
        if (warnings >= policy.count()) {
            Instant end = now.plus(Duration.ofDays(policy.suspendDays()));
            if (penalties.suspend(userId, reason, warningId, now, end)) {
                until = end;
            }
        }
        return new Penalty(warnings, policy.count(), policy.windowDays(), policy.suspendDays(), until);
    }

    private static String label(ReportCategory category) {
        return switch (category) {
            case BROKEN -> "비품 고장";
            case WINDOW_OPEN -> "창문 열림";
            case MESSY -> "어질러짐";
            case NOISE -> "소음";
        };
    }

    private void requireAdmin(long userId) {
        if (!reports.isAdmin(userId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
    }
}
