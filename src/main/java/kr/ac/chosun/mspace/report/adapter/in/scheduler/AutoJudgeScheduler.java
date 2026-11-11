package kr.ac.chosun.mspace.report.adapter.in.scheduler;

import kr.ac.chosun.mspace.report.application.AutoJudgeUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1분마다 판정 대기 신고를 판정기로 정한다 (FR-RPT-103 10차). 예약 정리와 같은 스위치(mspace.reservation.sweep.enabled)를
 * 따른다: 테스트는 꺼 두고 judgeOpen()을 직접 부른다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mspace.reservation.sweep.enabled", havingValue = "true")
class AutoJudgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoJudgeScheduler.class);

    private final AutoJudgeUseCase judge;

    AutoJudgeScheduler(AutoJudgeUseCase judge) {
        this.judge = judge;
    }

    @Scheduled(fixedDelayString = "${mspace.report.judge.interval}", initialDelayString = "${mspace.report.judge.interval}")
    void judge() {
        int done = judge.judgeOpen();
        if (done > 0) {
            log.info("신고 {}건 자동 판정", done);
        }
    }
}
