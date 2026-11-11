package kr.ac.chosun.mspace.report.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kr.ac.chosun.mspace.common.config.ReportJudgePolicy;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 학생 신고 자동 판정 (FR-RPT-103 10차: 관리자는 판정하지 않음, Q-28).
 * <ul>
 *   <li>판정기 호출은 트랜잭션 밖(외부 AI가 느려도 잠금을 쥐지 않게), 결과 적용만 한 트랜잭션</li>
 *   <li>사실·허위라도 확신도가 기준(0.8) 아래면 확인 불가로 정한다. 판정의 "확인 불가 = 벌칙 없음"과 같은 셈</li>
 *   <li>관리자가 먼저 판정했으면 건너뛴다(조건부 UPDATE). 판정 결과 알림·경고는 관리자 판정과 같다</li>
 * </ul>
 */
@Service
class AutoJudgeService implements AutoJudgeUseCase {

    private static final Logger log = LoggerFactory.getLogger(AutoJudgeService.class);

    private final AutoJudgePort port;
    private final ReportJudge judge;
    private final JudgeReportService reports;
    private final ReportJudgePolicy policy;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;
    private final Clock clock;

    AutoJudgeService(AutoJudgePort port, ReportJudge judge, JudgeReportService reports, ReportJudgePolicy policy,
                     NotificationInbox inbox, TransactionOperations tx, Clock clock) {
        this.port = port;
        this.judge = judge;
        this.reports = reports;
        this.policy = policy;
        this.inbox = inbox;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public int judgeOpen() {
        int done = 0;
        for (long id : port.openReports(clock.instant().minus(policy.waitAfter()), policy.batchSize())) {
            try {
                if (judgeOne(id)) {
                    done++;
                }
            } catch (RuntimeException e) {
                // 한 건이 실패해도 나머지는 판정한다. 대기로 남아 다음 번에 다시 본다
                log.warn("신고 자동 판정 실패, 다음 번에 다시 봄: report={} error={}", id, e.getClass().getSimpleName());
            }
        }
        return done;
    }

    private boolean judgeOne(long id) {
        Optional<ReportJudge.Case> c = port.loadCase(id);
        if (c.isEmpty()) {
            return false;
        }
        ReportJudge.Judgement j = judge.judge(c.get());
        if (j == null || j.verdict() == null || j.verdict() == ReportVerdict.OPEN
                || !(j.confidence() >= 0 && j.confidence() <= 1)) {
            throw new IllegalStateException("판정기 결과가 올바르지 않다 (판정 없음 또는 확신도 0~1 밖)");
        }
        ReportVerdict applied = j.verdict() != ReportVerdict.UNVERIFIED && j.confidence() < policy.minConfidence()
                ? ReportVerdict.UNVERIFIED : j.verdict();
        Optional<List<Notification>> stored = Objects.requireNonNull(tx.execute(st -> {
            Instant now = clock.instant();
            Optional<List<NewNotification>> out = reports.judgeBySystem(id, applied, now);
            out.ifPresent(n -> port.record(id, j, applied, now));
            return out.map(n -> inbox.store(n, now));
        }));
        stored.ifPresent(inbox::publish);
        return stored.isPresent();
    }
}
