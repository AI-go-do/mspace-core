package kr.ac.chosun.mspace.sensor.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.sensor.application.RecordSensorEventUseCase.SensorEvent;
import kr.ac.chosun.mspace.sensor.application.SensorEventPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 표는 V11__sensor_events.sql. 트랜잭션은 서비스가 연다 */
@Component
class JdbcSensorEventAdapter implements SensorEventPort {

    private static final Timestamp EPOCH = Timestamp.from(Instant.EPOCH);

    private final JdbcClient jdbc;

    JdbcSensorEventAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean roomExists(int roomId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM rooms WHERE id = :room)").param("room", roomId)
                .query(Boolean.class).single();
    }

    @Override
    public boolean exists(String eventId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sensor_event WHERE event_id = :id)")
                .param("id", eventId).query(Boolean.class).single();
    }

    @Override
    public boolean insert(SensorEvent e, Instant receivedAt) {
        // 같은 eventId가 동시에 오면 나중 쪽은 먼저 쪽 커밋을 기다렸다가 DO NOTHING → 0줄
        return jdbc.sql("""
                INSERT INTO sensor_event (event_id, room_id, device_id, kind, occupied, sources, ppm, source,
                                          observed_at, received_at)
                VALUES (:eventId, :room, :device, :kind, :occupied, CAST(:sources AS jsonb), :ppm, :source,
                        :observed, :received)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("eventId", e.eventId()).param("room", e.roomId()).param("device", e.deviceId())
                .param("kind", e.kind().name()).param("occupied", e.occupied()).param("sources", e.sources())
                .param("ppm", e.ppm()).param("source", e.source())
                .param("observed", Timestamp.from(e.observedAt())).param("received", Timestamp.from(receivedAt))
                .update() == 1;
    }

    @Override
    public Presence lockPresence(int roomId) {
        // 처음 보는 방: "있음", 시각은 모두 EPOCH(= 한 번도 못 받음 → 끊김으로 보여 "있음" 쪽)
        jdbc.sql("""
                INSERT INTO room_presence (room_id, occupied, changed_at, trusted_from, last_observed_at, last_received_at)
                VALUES (:room, true, :epoch, :epoch, :epoch, :epoch)
                ON CONFLICT (room_id) DO NOTHING
                """).param("room", roomId).param("epoch", EPOCH).update();
        return jdbc.sql("""
                SELECT room_id, occupied, changed_at, trusted_from, last_observed_at, last_received_at, ventilation_alert_at
                FROM room_presence WHERE room_id = :room FOR UPDATE
                """).param("room", roomId).query(JdbcSensorEventAdapter::presence).single();
    }

    @Override
    public void savePresence(Presence p) {
        jdbc.sql("""
                UPDATE room_presence
                SET occupied = :occupied, changed_at = :changed, trusted_from = :trusted,
                    last_observed_at = :observed, last_received_at = :received, ventilation_alert_at = :alert
                WHERE room_id = :room
                """)
                .param("room", p.roomId()).param("occupied", p.occupied())
                .param("changed", Timestamp.from(p.changedAt())).param("trusted", Timestamp.from(p.trustedFrom()))
                .param("observed", Timestamp.from(p.lastObservedAt()))
                .param("received", Timestamp.from(p.lastReceivedAt()))
                .param("alert", p.ventilationAlertAt() == null ? null : Timestamp.from(p.ventilationAlertAt()))
                .update();
    }

    @Override
    public List<Co2Reading> recentCo2(int roomId, int limit) {
        // 인덱스 sensor_event_room_recent (room_id, kind, observed_at DESC)
        return jdbc.sql("""
                SELECT event_id, ppm, observed_at FROM sensor_event
                WHERE room_id = :room AND kind = 'CO2'
                ORDER BY observed_at DESC, id DESC
                LIMIT :limit
                """).param("room", roomId).param("limit", limit)
                .query((rs, n) -> new Co2Reading(rs.getString("event_id"), rs.getInt("ppm"),
                        rs.getTimestamp("observed_at").toInstant()))
                .list();
    }

    @Override
    public List<Occupant> occupantsNow(int roomId, Instant now) {
        return jdbc.sql("""
                SELECT r.id, p.user_id, rm.name
                FROM reservation r
                JOIN reservation_participant p ON p.reservation_id = r.id AND p.active
                JOIN rooms rm ON rm.id = r.room_id
                WHERE r.room_id = :room AND r.status = 'IN_USE' AND r.start_at <= :now AND r.end_at > :now
                ORDER BY p.is_owner DESC, p.user_id
                """).param("room", roomId).param("now", Timestamp.from(now))
                .query((rs, n) -> new Occupant(rs.getLong(1), rs.getLong(2), rs.getString(3)))
                .list();
    }

    private static Presence presence(ResultSet rs, int n) throws SQLException {
        Timestamp alert = rs.getTimestamp("ventilation_alert_at");
        return new Presence(rs.getInt("room_id"), rs.getBoolean("occupied"),
                rs.getTimestamp("changed_at").toInstant(), rs.getTimestamp("trusted_from").toInstant(),
                rs.getTimestamp("last_observed_at").toInstant(), rs.getTimestamp("last_received_at").toInstant(),
                alert == null ? null : alert.toInstant());
    }
}
