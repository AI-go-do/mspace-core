package kr.ac.chosun.mspace.reservation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
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
    private final ExcuseNoShowsUseCase excuses;
    private final RelayUseCase relay;
    private final Clock clock;

    SweepReservationsService(ReservationSweepPort sweeps, ExcuseNoShowsUseCase excuses, RelayUseCase relay, Clock clock) {
        this.sweeps = sweeps;
        this.excuses = excuses;
        this.relay = relay;
        this.clock = clock;
    }

    @Override
    public Swept sweep() {
        var now = clock.instant();
        // 노쇼는 BOOKED만, 나머지 둘은 IN_USE만 건드린다. 각각 따로 커밋.
        // 자동 퇴실을 자리 비움보다 먼저: 끝 시각이 된 예약은 자리 비움으로 세지 않는다 (자리 비움 쪽도 end_at > now만 본다)
        // 한쪽이 잠금 대기 상한을 넘어도 다른 쪽은 돌고, 넘은 쪽은 다음 번(1분 뒤)에 다시 한다
        int noShows = step("노쇼", sweeps::markNoShows, now);
        // 장치 오류가 노쇼 처리보다 먼저 왔으면 방금 노쇼가 된 예약을 여기서 무른다 (방의 사과, Q-19)
        // 알림함 저장 등 잠금 말고 다른 오류가 나도 뒤 단계(자동 퇴실·자리 비움)는 돌게 여기서 막는다. 다음 번(1분 뒤)에 다시 한다
        try {
            step("장치 오류 노쇼 무르기", excuses::excuse, now);
        } catch (RuntimeException e) {
            log.error("예약 정리(장치 오류 노쇼 무르기) 실패, 다음 번에 다시", e);
        }
        int autoCheckedOut = step("자동 퇴실", sweeps::autoCheckout, now);
        var swept = new Swept(noShows, autoCheckedOut, step("자리 비움", sweeps::releaseAway, now));
        // 빈방 릴레이: 받을 시간(5분)이 지난 넘김을 다음 사람에게 (Q-18). 알림함 저장 등 다른 오류가 나도 정리 결과는 돌려준다
        try {
            step("릴레이 넘기기", relay::advance, now);
        } catch (RuntimeException e) {
            log.error("예약 정리(릴레이 넘기기) 실패, 다음 번에 다시", e);
        }
        if (swept.noShows() > 0 || swept.autoCheckedOut() > 0 || swept.awayReleased() > 0) {
            log.info("예약 정리: 노쇼 {}건, 자동 퇴실 {}건, 자리 비움 퇴실 {}건",
                    swept.noShows(), swept.autoCheckedOut(), swept.awayReleased());
        }
        return swept;
    }

    private static int step(String name, ToIntFunction<Instant> update, Instant now) {
        try {
            return update.applyAsInt(now);
        } catch (PessimisticLockingFailureException e) {
            // 잠금 대기 상한 초과(55P03 → CannotAcquireLockException)와 교착(40P01 → DeadlockLoserDataAccessException 등)을
            // 함께 잡는다. 정리는 예약 만들기 전에도 돌기 때문에, 정리가 실패해도 예약 만들기는 계속 가야 한다
            log.warn("예약 정리({}): 잠금 충돌({})로 이번에는 건너뜀, 다음 번에 다시", name, e.getClass().getSimpleName());
            return 0;
        }
    }
}
