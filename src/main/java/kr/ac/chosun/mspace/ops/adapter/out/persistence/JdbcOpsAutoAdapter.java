package kr.ac.chosun.mspace.ops.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.ops.application.OpsAutoPort;
import kr.ac.chosun.mspace.ops.domain.OpsRules.Span;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * AI 자동 처리 저장소. 방 5개라 1분마다 방 전체를 읽어도 가볍다.
 * 예약은 방·시간 인덱스를 쓴다.
 */
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
    public List<Room> rooms() {
        // 마지막 신호 = 재실·CO2 중 늦게 받은 것 (관리자 "지금" 탭 끊김과 같은 기준)
        return jdbc.sql("""
                        SELECT rm.id, rm.name, coalesce(pr.occupied, false) AS occupied, pr.changed_at,
                               nullif(greatest(pr.last_received_at, co2.received_at), 'epoch'::timestamptz) AS last_at,
                               pr.last_received_at AS presence_at
                        FROM rooms rm
                        LEFT JOIN room_presence pr ON pr.room_id = rm.id
                        LEFT JOIN LATERAL (SELECT received_at FROM sensor_event
                                           WHERE room_id = rm.id AND kind = 'CO2'
                                           ORDER BY received_at DESC LIMIT 1) co2 ON true
                        WHERE rm.is_active
                        ORDER BY rm.id
                        """)
                .query((rs, n) -> new Room(rs.getInt("id"), rs.getString("name"), rs.getBoolean("occupied"),
                        instant(rs.getTimestamp("changed_at")), instant(rs.getTimestamp("last_at")),
                        instant(rs.getTimestamp("presence_at"))))
                .list();
    }

    @Override
    public Map<Integer, List<Span>> bookings(Instant from, Instant to) {
        Map<Integer, List<Span>> out = new HashMap<>();
        jdbc.sql("""
                        SELECT room_id, start_at, least(end_at, coalesce(checked_out_at, end_at)) AS end_at
                        FROM reservation
                        WHERE status IN ('BOOKED', 'IN_USE', 'CHECKED_OUT', 'AUTO_CHECKED_OUT')
                          AND start_at <= :to AND least(end_at, coalesce(checked_out_at, end_at)) > :from
                        """)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query(rs -> {
                    out.computeIfAbsent(rs.getInt("room_id"), k -> new ArrayList<>())
                            .add(new Span(rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant()));
                });
        return out;
    }

    @Override
    public boolean record(String kind, int roomId, Long reservationId, Instant startedAt, Instant now, String detail) {
        return jdbc.sql("""
                        INSERT INTO ops_action (kind, room_id, reservation_id, started_at, detected_at, detail)
                        VALUES (:kind, :room, :rid, :started, :now, :detail)
                        ON CONFLICT ON CONSTRAINT ops_action_once DO NOTHING
                        """)
                .param("kind", kind).param("room", roomId).param("rid", reservationId)
                .param("started", Timestamp.from(startedAt)).param("now", Timestamp.from(now)).param("detail", detail)
                .update() == 1;
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
                        rs.getString("name"), (Long) rs.getObject("reservation_id", Long.class),
                        rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("detected_at").toInstant(),
                        rs.getString("detail"), rs.getInt("notified")))
                .list();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
