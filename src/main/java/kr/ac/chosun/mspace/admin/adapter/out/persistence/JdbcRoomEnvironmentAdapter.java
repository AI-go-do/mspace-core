package kr.ac.chosun.mspace.admin.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.admin.application.RoomEnvironmentPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcRoomEnvironmentAdapter implements RoomEnvironmentPort {

    private static final String ROOM_ID = "room_id";

    private final JdbcClient jdbc;

    JdbcRoomEnvironmentAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }

    @Override
    public List<Room> rooms() {
        return jdbc.sql("SELECT id, name FROM rooms WHERE is_active ORDER BY id")
                .query((rs, n) -> new Room(rs.getInt("id"), rs.getString("name"))).list();
    }

    @Override
    public List<PresenceRow> presence() {
        return jdbc.sql("SELECT room_id, occupied, last_received_at FROM room_presence")
                .query((rs, n) -> new PresenceRow(rs.getInt(ROOM_ID), rs.getBoolean("occupied"),
                        rs.getTimestamp("last_received_at").toInstant()))
                .list();
    }

    @Override
    public List<Latest> latest() {
        // 방마다 세 번 거꾸로 읽기(LIMIT 1). CO2는 V11 인덱스, 온도·습도는 V21 부분 인덱스(값 있는 줄만) 앞부분으로 끝난다
        return jdbc.sql("""
                        SELECT r.id AS room_id, c.ppm, c.observed_at AS ppm_at, t.temperature_c, h.humidity_pct,
                               c.received_at
                        FROM rooms r
                        LEFT JOIN LATERAL (SELECT ppm, observed_at, received_at FROM sensor_event
                                           WHERE room_id = r.id AND kind = 'CO2'
                                           ORDER BY observed_at DESC, id DESC LIMIT 1) c ON true
                        LEFT JOIN LATERAL (SELECT temperature_c FROM sensor_event
                                           WHERE room_id = r.id AND kind = 'CO2' AND temperature_c IS NOT NULL
                                           ORDER BY observed_at DESC, id DESC LIMIT 1) t ON true
                        LEFT JOIN LATERAL (SELECT humidity_pct FROM sensor_event
                                           WHERE room_id = r.id AND kind = 'CO2' AND humidity_pct IS NOT NULL
                                           ORDER BY observed_at DESC, id DESC LIMIT 1) h ON true
                        WHERE r.is_active
                        """)
                .query((rs, n) -> new Latest(rs.getInt(ROOM_ID), integer(rs, "ppm"), instant(rs, "ppm_at"),
                        decimal(rs, "temperature_c"), decimal(rs, "humidity_pct"), instant(rs, "received_at")))
                .list();
    }

    @Override
    public List<MinuteRow> series(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT r.id AS room_id, m.at,
                               round(avg(e.ppm))::int AS ppm,
                               round(avg(e.temperature_c), 1) AS temperature_c,
                               round(avg(e.humidity_pct), 1) AS humidity_pct
                        FROM rooms r
                        CROSS JOIN generate_series(CAST(:from AS timestamptz), CAST(:to AS timestamptz) - interval '1 minute',
                                                   interval '1 minute') AS m(at)
                        LEFT JOIN sensor_event e ON e.room_id = r.id AND e.kind = 'CO2'
                                                AND e.observed_at >= m.at AND e.observed_at < m.at + interval '1 minute'
                        WHERE r.is_active
                        GROUP BY r.id, m.at
                        ORDER BY r.id, m.at
                        """)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> new MinuteRow(rs.getInt(ROOM_ID), rs.getTimestamp("at").toInstant(),
                        integer(rs, "ppm"), decimal(rs, "temperature_c"), decimal(rs, "humidity_pct")))
                .list();
    }

    @Override
    public List<Booking> bookingsEndingAfter(Instant at) {
        return jdbc.sql("""
                        SELECT id, room_id, start_at, end_at, headcount, status FROM reservation
                        WHERE status IN ('BOOKED', 'IN_USE') AND end_at > :at
                        ORDER BY start_at, id
                        """)
                .param("at", Timestamp.from(at))
                .query((rs, n) -> new Booking(rs.getLong("id"), rs.getInt(ROOM_ID),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                        rs.getInt("headcount"), "IN_USE".equals(rs.getString("status"))))
                .list();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Double decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal v = rs.getBigDecimal(column);
        return v == null ? null : v.doubleValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
