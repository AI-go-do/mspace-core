package kr.ac.chosun.mspace.sensor.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.ac.chosun.mspace.sensor.application.LightLeftOnPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 빈 방 불 켜짐. 방 5개라 매분 읽어도 가볍다 (reservation은 방·시간 겹침 GiST, room_presence는 방마다 1줄) */
@Component
class JdbcLightLeftOnAdapter implements LightLeftOnPort {

    private final JdbcClient jdbc;

    JdbcLightLeftOnAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<Integer> idleRooms(Instant now) {
        return new HashSet<>(jdbc.sql("""
                SELECT p.room_id FROM room_presence p
                WHERE NOT p.occupied
                  AND NOT EXISTS (SELECT 1 FROM reservation r
                                  WHERE r.room_id = p.room_id AND r.status IN ('BOOKED', 'IN_USE')
                                    AND r.start_at <= :now AND r.end_at > :now)
                """).param("now", Timestamp.from(now)).query((rs, n) -> rs.getInt(1)).list());
    }

    @Override
    public Optional<LastTeam> lastTeam(int roomId) {
        return jdbc.sql("""
                        SELECT r.id, rm.name, r.checked_out_at,
                               EXISTS (SELECT 1 FROM notification n
                                       WHERE n.reservation_id = r.id AND n.kind = 'LIGHT_LEFT_ON') AS notified
                        FROM reservation r JOIN rooms rm ON rm.id = r.room_id
                        WHERE r.room_id = :room AND r.checked_out_at IS NOT NULL
                        ORDER BY r.checked_out_at DESC, r.id DESC
                        LIMIT 1
                        """).param("room", roomId)
                .query((rs, n) -> new LastTeam(rs.getLong("id"), rs.getString("name"),
                        rs.getTimestamp("checked_out_at").toInstant(), rs.getBoolean("notified"), List.of()))
                .optional()
                .map(t -> new LastTeam(t.reservationId(), t.roomName(), t.checkedOutAt(), t.notified(),
                        jdbc.sql("""
                                SELECT user_id FROM reservation_participant
                                WHERE reservation_id = :id
                                ORDER BY is_owner DESC, user_id
                                """).param("id", t.reservationId()).query((rs, n) -> rs.getLong(1)).list()));
    }
}
