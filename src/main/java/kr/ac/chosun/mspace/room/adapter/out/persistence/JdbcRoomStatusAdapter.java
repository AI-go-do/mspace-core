package kr.ac.chosun.mspace.room.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.room.application.RoomStatusPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 방 현황 읽기 전용. 예약은 끝나지 않은 것만, 재실은 방마다 한 줄이라 둘 다 작다 */
@Component
class JdbcRoomStatusAdapter implements RoomStatusPort {

    private final JdbcClient jdbc;

    JdbcRoomStatusAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ActiveReservation> findReservationsEndingAfter(Instant at) {
        // 대표자당 끝나지 않은 예약은 1건이라(one_active_per_owner) 많아야 회원 수만큼
        return jdbc.sql("""
                        SELECT room_id, start_at, end_at, status FROM reservation
                         WHERE status IN ('BOOKED', 'IN_USE') AND end_at > :at
                         ORDER BY start_at
                        """)
                .param("at", Timestamp.from(at))
                .query((rs, n) -> new ActiveReservation(rs.getInt("room_id"),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                        "IN_USE".equals(rs.getString("status"))))
                .list();
    }

    @Override
    public List<PresenceRow> findPresence() {
        // room_presence는 센서 기능(V11)이 고치는 표. 여기서는 읽기만 한다 (방마다 한 줄)
        return jdbc.sql("SELECT room_id, occupied, last_received_at FROM room_presence")
                .query((rs, n) -> new PresenceRow(rs.getInt("room_id"), rs.getBoolean("occupied"),
                        rs.getTimestamp("last_received_at").toInstant()))
                .list();
    }
}
