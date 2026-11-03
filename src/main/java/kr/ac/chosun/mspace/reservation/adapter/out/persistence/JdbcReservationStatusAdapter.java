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
    public boolean cancelIfBookedBeforeStart(long reservationId, long ownerId) {
        // 참여자 줄의 active는 트리거(reservation_sync_participants)가 false로 바꿔서, 참여자 시간도 같이 풀린다
        return jdbc.sql("""
                        UPDATE reservation SET status = 'CANCELED', canceled_at = now()
                        WHERE id = :id AND owner_id = :owner AND status = 'BOOKED' AND now() < start_at
                        """)
                .param("id", reservationId)
                .param("owner", ownerId)
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
    public boolean checkoutIfInUse(long reservationId, long ownerId) {
        return jdbc.sql("""
                        UPDATE reservation SET status = 'CHECKED_OUT', checked_out_at = now()
                        WHERE id = :id AND owner_id = :owner AND status = 'IN_USE'
                        """)
                .param("id", reservationId)
                .param("owner", ownerId)
                .update() == 1;
    }
}
