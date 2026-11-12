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
                            -- 마지막 상태는 센서가 마지막으로 신호를 보낸 때까지만 센다. 끊긴 센서의 마지막 "있음"이
                            -- 지금까지 이어져 "예약 없이 있던 시간"이 부풀던 것을 막는다 (신호 기록이 없으면 :cap까지)
                            SELECT ev.room_id, ev.occupied,
                                   greatest(ev.observed_at, CAST(:from AS timestamptz)) AS a,
                                   coalesce(lead(ev.observed_at) OVER (PARTITION BY ev.room_id ORDER BY ev.observed_at, ev.id),
                                            least(CAST(:cap AS timestamptz),
                                                  coalesce(rp.last_received_at, CAST(:cap AS timestamptz)))) AS b
                            FROM ev LEFT JOIN room_presence rp ON rp.room_id = ev.room_id
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

    @Override
    public Map<Integer, AlertRow> alerts(Instant from, Instant to, Instant now, int thresholdPpm,
                                         int resolvedWithinMinutes) {
        // 결과가 났는지는 기간 끝(to)이 아니라 지금(now)으로 본다. 지난 기간 23:50 알림도 지금은 결과가 나 있으니 센다.
        // 노쇼 직전 알림: 시작 시각이 기간 안인 예약(rooms()와 같은 기준). 아직 입실 마감 전이라 결과가 안 난 것은 뺀다.
        // 환기 알림: 같은 방·같은 시각 알림은 사람 수만큼 여러 줄이라 (방, 시각)으로 한 번만 센다(sensors()의 vent와 같음).
        //   확인 시간이 아직 안 지난 알림은 결과가 안 났으니 뺀다. 내려감 = 확인 시간 안에 기준 이하(<= threshold) 값이 하나라도 있음
        Map<Integer, AlertRow> out = new HashMap<>();
        jdbc.sql("""
                        WITH warned AS (
                            SELECT r.room_id, count(*) AS n, count(*) FILTER (WHERE r.checked_in_at IS NOT NULL) AS ok
                            FROM reservation r
                            WHERE r.start_at >= :from AND r.start_at < :to
                              AND EXISTS (SELECT 1 FROM notification_log l
                                          WHERE l.reservation_id = r.id AND l.kind = 'NO_SHOW_SOON')
                              AND (r.checked_in_at IS NOT NULL OR r.checkin_deadline <= :now)
                            GROUP BY r.room_id
                        ), vent AS (
                            SELECT DISTINCT r.room_id, n.created_at
                            FROM notification n JOIN reservation r ON r.id = n.reservation_id
                            WHERE n.kind = 'VENTILATE' AND n.created_at >= :from AND n.created_at < :to
                              AND n.created_at <= CAST(:now AS timestamptz) - make_interval(mins => :within)
                        ), resolved AS (
                            SELECT v.room_id, count(*) AS checked,
                                   count(*) FILTER (WHERE EXISTS (
                                       SELECT 1 FROM sensor_event e
                                       WHERE e.room_id = v.room_id AND e.kind = 'CO2' AND e.ppm <= :threshold
                                         AND e.observed_at > v.created_at
                                         AND e.observed_at <= v.created_at + make_interval(mins => :within))) AS ok
                            FROM vent v
                            GROUP BY v.room_id
                        )
                        SELECT rm.id, coalesce(w.n, 0) AS warned, coalesce(w.ok, 0) AS checked_in,
                               coalesce(x.checked, 0) AS vent_checked, coalesce(x.ok, 0) AS vent_ok
                        FROM rooms rm
                        LEFT JOIN warned w ON w.room_id = rm.id
                        LEFT JOIN resolved x ON x.room_id = rm.id
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("now", Timestamp.from(now))
                .param("threshold", thresholdPpm)
                .param("within", resolvedWithinMinutes)
                .query(rs -> {
                    out.put(rs.getInt("id"), new AlertRow(rs.getLong("warned"), rs.getLong("checked_in"),
                            rs.getLong("vent_checked"), rs.getLong("vent_ok")));
                });
        return out;
    }
}
