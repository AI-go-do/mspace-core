package kr.ac.chosun.mspace.reservation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;

/**
 * 노쇼·자동 퇴실. 상태가 바뀌면 트리거(reservation_sync_participants)가 참여자 줄도 풀어서
 * 방(no_room_overlap), 대표자 1건(one_active_per_owner), 참여자 시간(no_person_overlap)이 바로 비어요.
 * "지금"은 체크인과 같은 앱 Clock으로 잰다 (체크인은 KeypadEntryService가 Clock 시각으로 판정).
 */
@Service
class SweepReservationsService implements SweepReservationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(SweepReservationsService.class);

    private final ReservationSweepPort sweeps;
    private final Clock clock;

    SweepReservationsService(ReservationSweepPort sweeps, Clock clock) {
        this.sweeps = sweeps;
        this.clock = clock;
    }

    @Override
    public Swept sweep() {
        var now = clock.instant();
        // 두 UPDATE는 서로 다른 상태(BOOKED / IN_USE)만 건드려서 순서가 결과를 바꾸지 않는다. 각각 따로 커밋.
        // 한쪽이 잠금 대기 상한을 넘어도 다른 쪽은 돌고, 넘은 쪽은 다음 번(1분 뒤)에 다시 한다
        var swept = new Swept(step("노쇼", sweeps::markNoShows, now), step("자동 퇴실", sweeps::autoCheckout, now));
        if (swept.noShows() > 0 || swept.autoCheckedOut() > 0) {
            log.info("예약 정리: 노쇼 {}건, 자동 퇴실 {}건", swept.noShows(), swept.autoCheckedOut());
        }
        return swept;
    }

    private static int step(String name, ToIntFunction<Instant> update, Instant now) {
        try {
            return update.applyAsInt(now);
        } catch (CannotAcquireLockException e) {
            log.warn("예약 정리({}): 다른 작업이 예약을 잡고 있어 이번에는 건너뜀, 다음 번에 다시", name);
            return 0;
        }
    }
}
