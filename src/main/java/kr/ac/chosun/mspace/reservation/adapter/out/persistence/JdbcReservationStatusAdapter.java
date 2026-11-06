package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import kr.ac.chosun.mspace.reservation.application.ReservationStatusPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 예약 상태 바꾸기. SQL은 V1__init_schema.sql 끝 "5. 상태 변경은 조건부 UPDATE로"와 같다 */
@Component
class JdbcReservationStatusAdapter implements ReservationStatusPort {

    private final JdbcClient jdbc;

    JdbcReservationStatusAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean cancelIfBookedBeforeStart(long reservationId, long ownerId, int instantCancelMinutes) {
        // 즉시 시작은 누른 시각이 시작이라, 누른 뒤 instantCancelMinutes 안이고 입실 전(BOOKED)이면 취소할 수 있다 (V20, docs #57).
        // 입실 마감(시작 + 10분)을 넘지 않게 checkin_deadline도 같이 본다. 노쇼 정리는 now() > checkin_deadline만 봐서 겹치지 않는다
        // 참여자 줄의 active는 트리거(reservation_sync_participants)가 false로 바꿔서, 참여자 시간도 같이 풀린다
        return jdbc.sql("""
                        UPDATE reservation SET status = 'CANCELED', canceled_at = now()
                        WHERE id = :id AND owner_id = :owner AND status = 'BOOKED'
                          AND (now() < start_at
                               OR (is_instant AND now() < start_at + make_interval(mins => :instantMinutes)
                                   AND now() <= checkin_deadline))
                        """)
                .param("id", reservationId)
                .param("owner", ownerId)
                .param("instantMinutes", instantCancelMinutes)
                .update() == 1;
    }

    @Override
    public boolean checkInIfBooked(long reservationId, Instant now, int openBeforeMinutes) {
        return jdbc.sql("""
                        UPDATE reservation SET status = 'IN_USE', checked_in_at = :now
                        WHERE id = :id AND status = 'BOOKED'
                          AND :now >= start_at - make_interval(mins => :before) AND :now < end_at
                          AND :now <= checkin_deadline
                        """)
                .param("id", reservationId)
                .param("now", Timestamp.from(now))
                .param("before", openBeforeMinutes)
                .update() == 1;
    }

    @Override
    public boolean checkoutIfInUse(long reservationId, long ownerId, Instant now) {
        return jdbc.sql("""
                        UPDATE reservation SET status = 'CHECKED_OUT', checked_out_at = :now
                        WHERE id = :id AND owner_id = :owner AND status = 'IN_USE'
                        """)
                .param("id", reservationId)
                .param("owner", ownerId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }
}
