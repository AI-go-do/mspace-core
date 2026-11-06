package kr.ac.chosun.mspace.admin.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.admin.application.UsageStatsPort;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 관리자 통계 쿼리. 예약은 (room_id, start_at) 인덱스(V18), 센서는 (room_id, kind, observed_at) 인덱스(V11)로 기간만 읽는다.
 * 방 5개 × 하루 약 48칸이라 92일이어도 예약은 수만 줄 안. CO2는 1분마다 한 줄이라 92일 × 5방 ≈ 66만 줄까지 갈 수 있어
 * 집계 한 번에 수백 ms가 걸릴 수 있다 (확인 필요). 느려지면 하루 단위 요약 표를 따로 둔다.
 */
@Component
class JdbcUsageStatsAdapter implements UsageStatsPort {

    private final JdbcClient jdbc;

    JdbcUsageStatsAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }

    @Override
    public List<RoomRow> rooms(Instant from, Instant to, Instant cap) {
        // 이용 시간: 입실 ~ 퇴실. 사용 중이면 지금(cap)까지, 단 예약 끝을 넘지 않게
        return jdbc.sql("""
                        WITH r AS (
                            SELECT r.*, CASE WHEN r.checked_in_at IS NULL THEN NULL
                                ELSE extract(epoch FROM greatest(coalesce(r.checked_out_at, least(CAST(:cap AS timestamptz), r.end_at))
                                                                 - r.checked_in_at, interval '0')) / 60 END AS used_min
                            FROM reservation r
                            WHERE r.start_at >= :from AND r.start_at < :to
                        )
                        SELECT rm.id, rm.name,
                               count(r.id) AS total,
                               count(*) FILTER (WHERE r.status = 'CANCELED') AS canceled,
                               count(*) FILTER (WHERE r.status = 'BOOKED') AS pending,
                               count(*) FILTER (WHERE r.status = 'NO_SHOW') AS no_show,
                               count(*) FILTER (WHERE r.checked_in_at IS NOT NULL) AS used,
                               count(*) FILTER (WHERE r.forced_by IS NOT NULL) AS forced,
                               count(*) FILTER (WHERE r.status = 'AWAY_RELEASED') AS away,
                               count(*) FILTER (WHERE r.status = 'AUTO_CHECKED_OUT') AS auto_out,
                               coalesce(round(sum(extract(epoch FROM r.end_at - r.start_at) / 60)
                                              FILTER (WHERE r.status <> 'CANCELED')), 0) AS booked_min,
                               coalesce(round(sum(r.used_min)), 0) AS used_min,
                               count(*) FILTER (WHERE r.used_min * 2 < extract(epoch FROM r.end_at - r.start_at) / 60
                                                  AND r.status <> 'IN_USE') AS short_uses
                        FROM rooms rm
                        LEFT JOIN r ON r.room_id = rm.id
                        GROUP BY rm.id, rm.name
                        ORDER BY rm.id
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("cap", Timestamp.from(cap))
                .query((rs, n) -> new RoomRow(rs.getInt("id"), rs.getString("name"), rs.getLong("total"),
                        rs.getLong("canceled"), rs.getLong("pending"), rs.getLong("no_show"), rs.getLong("used"),
                        rs.getLong("forced"), rs.getLong("away"), rs.getLong("auto_out"), rs.getLong("booked_min"),
                        rs.getLong("used_min"), rs.getLong("short_uses")))
                .list();
    }

    @Override
    public Map<Integer, Long> uniqueUsers(Instant from, Instant to) {
        Map<Integer, Long> out = new HashMap<>();
        jdbc.sql("""
                        SELECT coalesce(r.room_id, 0) AS room, count(DISTINCT p.user_id) AS n
                        FROM reservation r JOIN reservation_participant p ON p.reservation_id = r.id
                        WHERE r.start_at >= :from AND r.start_at < :to AND r.checked_in_at IS NOT NULL
                        GROUP BY GROUPING SETS ((r.room_id), ())
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((RowCallbackHandler) rs -> out.put(rs.getInt("room"), rs.getLong("n")));
        return out;
    }

    @Override
    public List<HourRow> byHour(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT extract(hour FROM r.start_at AT TIME ZONE 'Asia/Seoul')::int AS h,
                               count(*) FILTER (WHERE r.status <> 'CANCELED') AS reservations,
                               count(*) FILTER (WHERE r.checked_in_at IS NOT NULL) AS used
                        FROM reservation r
                        WHERE r.start_at >= :from AND r.start_at < :to
                        GROUP BY h
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new HourRow(rs.getInt("h"), rs.getLong("reservations"), rs.getLong("used")))
                .list();
    }

    @Override
    public Map<Integer, SensorRow> sensors(Instant from, Instant cap, int thresholdPpm) {
        // 재실: 기간 안 기록 + 기간 시작 직전 마지막 기록(시작 때 상태). 다음 기록까지를 그 상태로 본다.
        // "있음" 구간(multirange)에서 입실한 예약 구간을 빼면 예약 없이 사람이 있던 시간
        Map<Integer, SensorRow> out = new HashMap<>();
        jdbc.sql("""
                        WITH ev AS (
                            SELECT room_id, occupied, observed_at, id FROM sensor_event
                            WHERE kind = 'OCCUPANCY' AND observed_at >= :from AND observed_at < :cap
                            UNION ALL
                            SELECT rm.id, last.occupied, last.observed_at, last.id FROM rooms rm
                            CROSS JOIN LATERAL (
                                SELECT occupied, observed_at, id FROM sensor_event
                                WHERE room_id = rm.id AND kind = 'OCCUPANCY' AND observed_at < :from
                                ORDER BY observed_at DESC LIMIT 1) last
                        ), seg AS (
                            SELECT room_id, occupied,
                                   greatest(observed_at, CAST(:from AS timestamptz)) AS a,
                                   coalesce(lead(observed_at) OVER (PARTITION BY room_id ORDER BY observed_at, id),
                                            CAST(:cap AS timestamptz)) AS b
                            FROM ev
                        ), occ AS (
                            SELECT room_id, range_agg(tstzrange(a, b)) AS r FROM seg
                            WHERE occupied AND b > a GROUP BY room_id
                        ), used AS (
                            SELECT room_id, range_agg(tstzrange(greatest(checked_in_at, CAST(:from AS timestamptz)),
                                                                least(coalesce(checked_out_at, :cap), :cap))) AS r
                            FROM reservation
                            WHERE checked_in_at IS NOT NULL AND checked_in_at < :cap
                              AND coalesce(checked_out_at, :cap) > :from
                              AND least(coalesce(checked_out_at, :cap), :cap) > greatest(checked_in_at, CAST(:from AS timestamptz))
                            GROUP BY room_id
                        ), co2 AS (
                            SELECT room_id, count(*) AS n, coalesce(sum(ppm), 0) AS s, max(ppm) AS mx,
                                   count(*) FILTER (WHERE ppm > :threshold) AS over
                            FROM sensor_event
                            WHERE kind = 'CO2' AND observed_at >= :from AND observed_at < :cap
                            GROUP BY room_id
                        ), vent AS (
                            SELECT r.room_id, count(DISTINCT n.created_at) AS n
                            FROM notification n JOIN reservation r ON r.id = n.reservation_id
                            WHERE n.kind = 'VENTILATE' AND n.created_at >= :from AND n.created_at < :cap
                            GROUP BY r.room_id
                        )
                        SELECT rm.id, coalesce(co2.n, 0) AS co2_n, coalesce(co2.s, 0) AS co2_s, co2.mx AS co2_max,
                               coalesce(co2.over, 0) AS co2_over, coalesce(vent.n, 0) AS vent,
                               coalesce((SELECT round(sum(extract(epoch FROM upper(x) - lower(x))) / 60)
                                         FROM unnest(occ.r) x), 0) AS occ_min,
                               coalesce((SELECT round(sum(extract(epoch FROM upper(x) - lower(x))) / 60)
                                         FROM unnest(occ.r - coalesce(used.r, '{}'::tstzmultirange)) x), 0) AS alone_min
                        FROM rooms rm
                        LEFT JOIN occ ON occ.room_id = rm.id
                        LEFT JOIN used ON used.room_id = rm.id
                        LEFT JOIN co2 ON co2.room_id = rm.id
                        LEFT JOIN vent ON vent.room_id = rm.id
                        """)
                .param("from", Timestamp.from(from))
                .param("cap", Timestamp.from(cap))
                .param("threshold", thresholdPpm)
                .query(rs -> {
                    int max = rs.getInt("co2_max");
                    out.put(rs.getInt("id"), new SensorRow(rs.getLong("co2_n"), rs.getLong("co2_s"),
                            rs.wasNull() ? null : max, rs.getLong("co2_over"), rs.getLong("vent"),
                            rs.getLong("occ_min"), rs.getLong("alone_min")));
                });
        return out;
    }
}
