package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.reservation.application.ExcuseNoShowsPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 장치 오류 노쇼 무르기 (V23). device_fault_attempt는 device 기능이 채운다 */
@Component
class JdbcExcuseNoShowsAdapter implements ExcuseNoShowsPort {

    private final JdbcClient jdbc;

    JdbcExcuseNoShowsAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Excused> excuseMatched(Instant now) {
        return jdbc.sql("""
                        UPDATE reservation r SET no_show_excused_at = :now
                        FROM rooms rm
                        WHERE r.status = 'NO_SHOW' AND r.no_show_excused_at IS NULL AND rm.id = r.room_id
                          AND EXISTS (SELECT 1 FROM device_fault_attempt a WHERE a.reservation_id = r.id)
                        RETURNING r.id, r.owner_id, rm.name, r.start_at, least(r.checkin_deadline, r.end_at) AS no_show_at
                        """)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new Excused(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("no_show_at").toInstant()))
                .list();
    }

    @Override
    public boolean liftSuspension(long ownerId, Instant noShowAt, Instant now) {
        // 끝을 지금으로 당기면 정지 기간 CHECK(시작 < 끝)에 걸리는 "지금 막 시작한" 정지는 지운다
        int deleted = jdbc.sql("""
                        DELETE FROM user_suspension
                        WHERE user_id = :owner AND reason = 'NO_SHOW' AND starts_at >= :noShowAt AND starts_at >= :now
                        """)
                .param("owner", ownerId).param("noShowAt", Timestamp.from(noShowAt)).param("now", Timestamp.from(now))
                .update();
        int shortened = jdbc.sql("""
                        UPDATE user_suspension SET ends_at = :now
                        WHERE user_id = :owner AND reason = 'NO_SHOW' AND starts_at >= :noShowAt
                          AND starts_at < :now AND ends_at > :now
                        """)
                .param("owner", ownerId).param("noShowAt", Timestamp.from(noShowAt)).param("now", Timestamp.from(now))
                .update();
        return deleted + shortened > 0;
    }

    @Override
    public List<Long> members(long reservationId) {
        return jdbc.sql("""
                        SELECT user_id FROM reservation_participant WHERE reservation_id = :id
                        ORDER BY is_owner DESC, user_id
                        """)
                .param("id", reservationId).query((rs, n) -> rs.getLong(1)).list();
    }
}
