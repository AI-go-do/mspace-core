package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;

/**
 * 시간이 지난 예약을 한꺼번에 바꾸는 DB 일. 구현은 adapter/out/persistence.
 * 둘 다 "지금 상태가 맞을 때만" 바꾸는 조건부 UPDATE라, 두 번 돌거나 서버 두 대가 같이 돌려도 한 번만 바뀐다.
 */
public interface ReservationSweepPort {

    /** BOOKED인데 체크인 마감(checkin_deadline)이 지난 예약 → NO_SHOW. 같은 트랜잭션에서 노쇼 벌칙(FR-RSV-106) 정지도 건다. 바꾼 건수 */
    int markNoShows(Instant now);

    /** IN_USE인데 끝나는 시각이 된 예약 → AUTO_CHECKED_OUT (퇴실 시각 = 끝나는 시각). 바꾼 건수 */
    int autoCheckout(Instant now);
}
