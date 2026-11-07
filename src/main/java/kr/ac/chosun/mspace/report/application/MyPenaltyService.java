package kr.ac.chosun.mspace.report.application;

import java.time.Clock;
import java.time.Instant;
import kr.ac.chosun.mspace.common.config.ReportPenaltyPolicy;
import kr.ac.chosun.mspace.report.application.ReportPenaltyPort.WarningReason;
import org.springframework.stereotype.Service;

/** 내 경고 횟수. 판정 때 정지를 거는 셈(countWarnings)을 그대로 써서 화면 숫자와 실제 정지가 어긋나지 않게 한다 */
@Service
public class MyPenaltyService implements MyPenaltyUseCase {

    private final ReportPenaltyPort penalties;
    private final ReportPenaltyPolicy policy;
    private final Clock clock;

    public MyPenaltyService(ReportPenaltyPort penalties, ReportPenaltyPolicy policy, Clock clock) {
        this.penalties = penalties;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public MyPenalty mine(long userId) {
        Instant now = clock.instant();
        return new MyPenalty(
                penalties.countWarnings(userId, WarningReason.VIOLATION, now, policy.windowDays()),
                penalties.countWarnings(userId, WarningReason.FALSE_REPORT, now, policy.windowDays()),
                policy.count(), policy.windowDays(),
                penalties.useSuspendedUntil(userId, now).orElse(null),
                penalties.reportSuspendedUntil(userId, now).orElse(null));
    }
}
