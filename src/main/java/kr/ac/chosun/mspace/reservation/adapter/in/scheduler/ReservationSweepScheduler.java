package kr.ac.chosun.mspace.reservation.adapter.in.scheduler;

import kr.ac.chosun.mspace.reservation.application.SweepReservationsUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1분마다 노쇼·자동 퇴실 (FR-RSV-101, 102). mspace.reservation.sweep.enabled=false면 만들지 않는다
 * (테스트는 시계를 직접 옮기며 sweep()을 부르므로 끈다: src/test/resources/config/application.yml).
 * 앞 번이 끝난 뒤 interval만큼 쉬고 다시 돈다(fixedDelay). 한 번이 길어져도 겹쳐 돌지 않는다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mspace.reservation.sweep.enabled", havingValue = "true")
class ReservationSweepScheduler {

    private final SweepReservationsUseCase sweeps;

    ReservationSweepScheduler(SweepReservationsUseCase sweeps) {
        this.sweeps = sweeps;
    }

    @Scheduled(fixedDelayString = "${mspace.reservation.sweep.interval}",
            initialDelayString = "${mspace.reservation.sweep.interval}")
    void sweep() {
        sweeps.sweep();
    }
}
