package kr.ac.chosun.mspace.ops.adapter.in.scheduler;

import kr.ac.chosun.mspace.ops.application.OpsAutoUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1분마다 AI 자동 처리 5개를 본다 (11/12 지영). 예약 정리와 같은 스위치(mspace.reservation.sweep.enabled)를 따른다:
 * 테스트는 꺼 두고 check()를 직접 부른다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mspace.reservation.sweep.enabled", havingValue = "true")
class OpsAutoScheduler {

    private static final Logger log = LoggerFactory.getLogger(OpsAutoScheduler.class);

    private final OpsAutoUseCase ops;

    OpsAutoScheduler(OpsAutoUseCase ops) {
        this.ops = ops;
    }

    @Scheduled(fixedDelayString = "${mspace.ops.interval}", initialDelayString = "${mspace.ops.interval}")
    void check() {
        int done = ops.check();
        if (done > 0) {
            log.info("AI 자동 처리 {}건", done);
        }
    }
}
