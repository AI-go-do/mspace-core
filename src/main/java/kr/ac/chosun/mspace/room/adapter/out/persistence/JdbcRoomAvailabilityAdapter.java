package kr.ac.chosun.mspace.room.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 읽기 전용이라 엔티티 없이 SQL 두 줄로 가져온다.
 * 예약 엔티티는 예약 생성(#4)에서 만든다.
 */
@Component
class JdbcRoomAvailabilityAdapter implements RoomAvailabilityPort {

    private final JdbcClient jdbc;

    JdbcRoomAvailabilityAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RoomInfo> findActiveRooms() {
        return jdbc.sql("SELECT id, name, min_people, max_people FROM rooms WHERE is_active ORDER BY id")
                .query((rs, n) -> new RoomInfo(rs.getInt("id"), rs.getString("name"),
                        rs.getInt("min_people"), rs.getInt("max_people")))
                .list();
    }

    @Override
    public List<BookedTime> findActiveReservations(Instant from, Instant to) {
        // 끝나지 않은 예약만. 겹침 제약(no_room_overlap)의 WHERE와 같은 조건
        return jdbc.sql("""
                        SELECT room_id, start_at, end_at FROM reservation
                         WHERE status IN ('BOOKED', 'IN_USE')
                           AND start_at < :to AND end_at > :from
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new BookedTime(rs.getInt("room_id"),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant()))
                .list();
    }
}
