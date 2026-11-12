package kr.ac.chosun.mspace.modelscore.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.modelscore.application.ModelScorePort;
import kr.ac.chosun.mspace.modelscore.application.ModelScoreUseCase.Kind;
import kr.ac.chosun.mspace.modelscore.application.ModelScoreUseCase.Score;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcModelScoreAdapter implements ModelScorePort {

    private final JdbcClient jdbc;

    JdbcModelScoreAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean roomExists(int roomId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM rooms WHERE id = :room)").param("room", roomId)
                .query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public boolean insert(Score s, Instant receivedAt) {
        return jdbc.sql("""
                        INSERT INTO model_score (event_id, kind, model, accuracy, accuracy3, cases, serious,
                                                 median_seconds, measured_at, room_id, device_id, received_at)
                        VALUES (:event, :kind, :model, :accuracy, :accuracy3, :cases, :serious,
                                :median, :measured, :room, :device, :received)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("event", s.eventId()).param("kind", s.kind().name()).param("model", s.model())
                .param("accuracy", s.accuracy()).param("accuracy3", s.accuracy3()).param("cases", s.cases())
                .param("serious", s.serious()).param("median", s.medianSeconds())
                .param("measured", Timestamp.from(s.measuredAt())).param("room", s.roomId())
                .param("device", s.deviceId()).param("received", Timestamp.from(receivedAt))
                .update() == 1;
    }

    @Override
    public List<Score> recent(Kind kind, int limit) {
        return jdbc.sql("""
                        SELECT event_id, kind, model, accuracy, accuracy3, cases, serious, median_seconds,
                               measured_at, room_id, device_id
                        FROM model_score WHERE kind = :kind
                        ORDER BY measured_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("kind", kind.name()).param("limit", limit)
                .query(JdbcModelScoreAdapter::row).list();
    }

    private static Score row(ResultSet rs, int n) throws SQLException {
        return new Score(rs.getString(1), Kind.valueOf(rs.getString(2)), rs.getString(3), rs.getFloat(4),
                rs.getObject(5, Float.class), rs.getInt(6), rs.getObject(7, Integer.class), rs.getFloat(8),
                rs.getTimestamp(9).toInstant(), rs.getInt(10), rs.getString(11));
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }
}
