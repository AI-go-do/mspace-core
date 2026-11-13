package kr.ac.chosun.mspace.ops.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import kr.ac.chosun.mspace.ops.application.AiScorePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 실제 처리 채점 (11/13). 읽을 때마다 계산한다(따로 저장 안 함). 방 5개라 처리 수가 적어 가볍다.
 * 맞음 기준 (측정 때 정답 기준과 같게, 11/13 지영에게 보낸 표):
 * <ul>
 *   <li>미리 환기: 알림 뒤 8분 안에 CO2 950 이상</li>
 *   <li>예약 전 더움: 시작 2분 전 ~ 5분 뒤 온도 최고가 28도 이상 (값이 없으면 채점 안 함)</li>
 *   <li>센서 고장: 기록 뒤 10분 동안 그 방에서 받은 값이 없음</li>
 *   <li>시간 넘김·예약 없는 사용: 기록 5분 뒤 마지막 재실 값이 "있음" (값이 없으면 채점 안 함)</li>
 *   <li>신고 판정: AI가 정한 신고로 간 경고가 철회되지 않음 (철회되면 틀림으로 바뀜)</li>
 *   <li>경고 철회 판단: AI가 유지한 경고를 관리자가 철회하지 않음</li>
 * </ul>
 */
@Component
class JdbcAiScoreAdapter implements AiScorePort {

    /** ops_action 한 종류: ready = 결과 볼 시간이 됨, ok = 맞음(null이면 채점 못 함) */
    private static final String OPS = """
            SELECT count(*) FILTER (WHERE ready AND ok) AS r,
                   count(*) FILTER (WHERE ready AND ok IS NOT NULL) AS t,
                   count(*) FILTER (WHERE NOT ready) AS p
            FROM (SELECT :now >= %s AS ready, (%s) AS ok FROM ops_action a WHERE a.kind = '%s') x
            """;

    private static final String LAST_PRESENCE = """
            SELECT e.occupied FROM sensor_event e
            WHERE e.room_id = a.room_id AND e.kind = 'OCCUPANCY' AND e.observed_at <= a.detected_at + interval '5 minutes'
            ORDER BY e.observed_at DESC, e.id DESC LIMIT 1""";

    private final JdbcClient jdbc;

    JdbcAiScoreAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }

    @Override
    public Map<String, Graded> grade(Instant now) {
        Map<String, Graded> out = new HashMap<>();
        out.put("VENT_SOON", ops("VENT_SOON", "a.detected_at + interval '8 minutes'", """
                EXISTS (SELECT 1 FROM sensor_event e
                        WHERE e.room_id = a.room_id AND e.kind = 'CO2' AND e.ppm >= 950
                          AND e.observed_at > a.detected_at AND e.observed_at <= a.detected_at + interval '8 minutes')""",
                now));
        out.put("ROOM_HOT", ops("ROOM_HOT", "a.started_at + interval '5 minutes'", """
                SELECT max(e.temperature_c) >= 28 FROM sensor_event e
                WHERE e.room_id = a.room_id AND e.kind = 'CO2' AND e.temperature_c IS NOT NULL
                  AND e.observed_at BETWEEN a.started_at - interval '2 minutes' AND a.started_at + interval '5 minutes'""",
                now));
        out.put("SENSOR_DOWN", ops("SENSOR_DOWN", "a.detected_at + interval '10 minutes'", """
                NOT EXISTS (SELECT 1 FROM sensor_event e
                            WHERE e.room_id = a.room_id AND e.received_at > a.detected_at
                              AND e.received_at <= a.detected_at + interval '10 minutes')""",
                now));
        out.put("OVERTIME", ops("OVERTIME", "a.detected_at + interval '5 minutes'", LAST_PRESENCE, now));
        out.put("UNBOOKED", ops("UNBOOKED", "a.detected_at + interval '5 minutes'", LAST_PRESENCE, now));
        out.put("REPORT_JUDGE", jdbc.sql("""
                        SELECT count(*) FILTER (WHERE w.withdrawn_at IS NULL) AS r, count(*) AS t, 0 AS p
                        FROM report_judgement j LEFT JOIN user_warning w ON w.report_id = j.report_id
                        WHERE j.created_at <= :now
                        """).param("now", Timestamp.from(now)).query(JdbcAiScoreAdapter::row).single());
        // 사람이 닫은 요청(관리자 철회 reviewer=admin, 이미 철회돼 닫힘 reviewer=withdrawn)은 AI 판단이 아니라 뺀다
        out.put("APPEAL_REVIEW", jdbc.sql("""
                        SELECT count(*) FILTER (WHERE ap.status <> 'PENDING'
                                                  AND NOT (ap.status = 'KEPT' AND w.withdrawn_by IS NOT NULL)) AS r,
                               count(*) FILTER (WHERE ap.status <> 'PENDING') AS t,
                               count(*) FILTER (WHERE ap.status = 'PENDING') AS p
                        FROM warning_appeal ap JOIN user_warning w ON w.id = ap.warning_id
                        WHERE coalesce(ap.reviewer, '') NOT IN ('admin', 'withdrawn') AND ap.created_at <= :now
                        """).param("now", Timestamp.from(now)).query(JdbcAiScoreAdapter::row).single());
        return out;
    }

    private Graded ops(String kind, String readyAt, String ok, Instant now) {
        return jdbc.sql(OPS.formatted(readyAt, ok, kind)).param("now", Timestamp.from(now))
                .query(JdbcAiScoreAdapter::row).single();
    }

    private static Graded row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Graded(rs.getInt("r"), rs.getInt("t"), rs.getInt("p"));
    }
}
