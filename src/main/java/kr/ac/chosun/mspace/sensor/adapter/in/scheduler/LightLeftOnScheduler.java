package kr.ac.chosun.mspace.sensor.adapter.in.scheduler;

import kr.ac.chosun.mspace.sensor.application.LightLeftOnUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1분마다 빈 방 불 켜짐을 본다 (11/12 지영). 예약 정리와 같은 스위치(mspace.reservation.sweep.enabled)를 따른다:
 * 테스트는 꺼 두고 check()를 직접 부른다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mspace.reservation.sweep.enabled", havingValue = "true")
class LightLeftOnScheduler {

    private static final Logger log = LoggerFactory.getLogger(LightLeftOnScheduler.class);

    private final LightLeftOnUseCase light;

    LightLeftOnScheduler(LightLeftOnUseCase light) {
        this.light = light;
    }

    @Scheduled(fixedDelayString = "${mspace.sensor.light-left-on.interval}",
            initialDelayString = "${mspace.sensor.light-left-on.interval}")
    void check() {
        int sent = light.check();
        if (sent > 0) {
            log.info("불 켜짐 알림 {}건", sent);
        }
    }
}
