package kr.ac.chosun.mspace.ops.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.ops.application.OpsAutoPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** AI 자동 처리 저장소 */
@Component
class JdbcOpsAutoAdapter implements OpsAutoPort {

    private final JdbcClient jdbc;

    JdbcOpsAutoAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }

    @Override
    public List<Row> actions(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT a.id, a.kind, a.room_id, rm.name, a.reservation_id, a.started_at, a.detected_at,
                               a.detail, a.notified
                        FROM ops_action a JOIN rooms rm ON rm.id = a.room_id
                        WHERE a.detected_at >= :from AND a.detected_at < :to
                        ORDER BY a.detected_at DESC, a.id DESC
                        """)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("kind"), rs.getInt("room_id"),
                        rs.getString("name"), rs.getObject("reservation_id", Long.class),
                        rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("detected_at").toInstant(),
                        rs.getString("detail"), rs.getInt("notified")))
                .list();
    }
}
