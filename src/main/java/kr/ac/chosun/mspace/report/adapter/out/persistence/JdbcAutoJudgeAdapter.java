package kr.ac.chosun.mspace.report.adapter.out.persistence;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.report.application.AutoJudgePort;
import kr.ac.chosun.mspace.report.application.ReportJudge;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAutoJudgeAdapter implements AutoJudgePort {

    private static final Duration AIR_BEFORE_OUT = Duration.ofMinutes(15);
    private static final Duration AIR_MAX = Duration.ofHours(3);

    private final JdbcClient jdbc;

    JdbcAutoJudgeAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Long> openReports(Instant createdBefore, Instant previousOutBefore, int limit) {
        // 카메라 신고(source CAMERA)는 core가 만들면서 바로 정한다. 소음은 판정 대기 대상이 아님
        // 어질러짐: 앞 팀(loadCase와 같은 규칙)이 찾으러 올 시간이 지나야 본다. 찾아갔으면 경고하지 않으려고
        return jdbc.sql("""
                        SELECT p.id FROM report p
                        WHERE p.verdict = 'OPEN' AND p.source = 'USER' AND p.category <> 'NOISE' AND p.created_at <= :before
                          AND (p.category <> 'MESSY' OR NOT EXISTS (
                              SELECT 1 FROM (
                                  SELECT r.checked_out_at FROM reservation r
                                  WHERE r.room_id = p.room_id
                                    AND r.id IS DISTINCT FROM p.reporter_reservation_id
                                    AND r.checked_in_at IS NOT NULL AND r.checked_in_at <= p.created_at
                                  ORDER BY r.checked_in_at DESC, r.id DESC
                                  LIMIT 1) prev
                              WHERE prev.checked_out_at > :outBefore))
                        ORDER BY p.created_at, p.id
                        LIMIT :limit
                        """)
                .param("before", Timestamp.from(createdBefore)).param("outBefore", Timestamp.from(previousOutBefore))
                .param("limit", limit)
                .query((rs, n) -> rs.getLong(1)).list();
    }

    @Override
    public Optional<ReportJudge.Case> loadCase(long reportId) {
        // 앞 팀 = 경고 받을 예약과 같은 규칙(FR-RPT-104): 신고 전에 그 방에 입실한 예약 중 가장 늦은 것, 신고자 예약 제외
        return jdbc.sql("""
                        SELECT p.id, p.category, p.content, p.room_id, p.created_at, prev.id AS prev_id, prev.checked_out_at AS prev_out,
                               COALESCE(prev.checked_in_at <= mine.checked_in_at, false) AS prev_first,
                               EXISTS (SELECT 1 FROM reservation_photo f
                                       WHERE f.reservation_id = p.reporter_reservation_id AND f.kind = 'CHECK_IN') AS check_in,
                               EXISTS (SELECT 1 FROM reservation_photo f
                                       WHERE f.reservation_id = prev.id AND f.kind = 'CHECK_OUT') AS prev_check_out,
                               t.result, t.items, t.confidences, COALESCE(t.phase = 'RECHECK', false) AS rechecked,
                               EXISTS (SELECT 1 FROM user_warning w JOIN report c ON c.id = w.report_id
                                       WHERE w.reservation_id = prev.id AND c.category = 'MESSY') AS prev_warned,
                               EXISTS (SELECT 1 FROM retrieve_pass q
                                       WHERE q.reservation_id = prev.id AND q.first_used_at IS NOT NULL) AS prev_retrieved
                        FROM report p
                        LEFT JOIN LATERAL (
                            SELECT r.id, r.checked_in_at, r.checked_out_at FROM reservation r
                            WHERE r.room_id = p.room_id
                              AND r.id IS DISTINCT FROM p.reporter_reservation_id
                              AND r.checked_in_at IS NOT NULL AND r.checked_in_at <= p.created_at
                            ORDER BY r.checked_in_at DESC, r.id DESC
                            LIMIT 1) prev ON true
                        LEFT JOIN reservation mine ON mine.id = p.reporter_reservation_id
                        LEFT JOIN LATERAL (
                            -- 다시 확인(RECHECK)이 있으면 그것이 더 늦은 사실이라 먼저 본다.
                            -- 앞 팀이 신고자보다 늦게 입실했으면(다음 예약 조기 입실) 앞 팀이 아니라 카메라 결과를 쓰지 않는다
                            SELECT phase, result, items, confidences FROM tidy_check
                            WHERE reservation_id = prev.id
                              AND (SELECT checked_in_at FROM reservation WHERE id = prev.id) <= mine.checked_in_at
                            ORDER BY (phase = 'RECHECK') DESC
                            LIMIT 1) t ON true
                        WHERE p.id = :id AND p.verdict = 'OPEN' AND p.category <> 'NOISE'
                        """)
                .param("id", reportId)
                .query((rs, n) -> {
                    // 앞 팀이 신고자보다 늦게 입실했으면(다음 예약 조기 입실) 센서 구간에 신고자가 있던 때가 섞여
                    // 앞 팀 탓인지 알 수 없다 → 카메라 결과와 같이 센서 값도 넘기지 않는다 (CodeRabbit #121)
                    Timestamp prevOut = rs.getBoolean("prev_first") ? rs.getTimestamp("prev_out") : null;
                    RoomRow room = new RoomRow(rs.getInt("room_id"), rs.getTimestamp("created_at").toInstant(),
                            prevOut == null ? null : prevOut.toInstant());
                    Array conf = rs.getArray("confidences");
                    List<Double> confidences = new ArrayList<>();
                    if (conf != null) {
                        for (Float f : (Float[]) conf.getArray()) {
                            confidences.add(f == null ? null : f.doubleValue());
                        }
                    }
                    return new Row(rs.getLong("id"), rs.getString("category"), rs.getString("content"),
                            rs.getBoolean("check_in"), rs.getBoolean("prev_check_out"), rs.getString("result"),
                            Arrays.asList(strings(rs.getArray("items"))), confidences,
                            rs.getBoolean("prev_warned"), rs.getBoolean("prev_retrieved"),
                            rs.getObject("prev_id", Long.class), rs.getBoolean("rechecked"), room);
                })
                .optional()
                .map(r -> new ReportJudge.Case(r.id, r.category, r.content, r.checkIn, r.prevCheckOut, r.result,
                        r.items, r.confidences, r.prevWarned, r.prevRetrieved, r.prevId, r.rechecked,
                        "WINDOW_OPEN".equals(r.category) ? roomAir(r.room) : null));
    }

    private record Row(long id, String category, String content, boolean checkIn, boolean prevCheckOut, String result,
                       List<String> items, List<Double> confidences, boolean prevWarned, boolean prevRetrieved,
                       Long prevId, boolean rechecked, RoomRow room) {
    }

    private record RoomRow(int roomId, Instant reportedAt, Instant previousOut) {
    }

    /**
     * 창문 열림 판정용 방 CO2·온도 (FR-RPT-103, 11/12 지영 "창문 열림이랑 어질러짐"). 앞 팀 퇴실 15분 전부터 신고 때까지
     * 5분씩 평균, 최대 3시간. 앞 팀이 없거나, 신고자보다 늦게 입실했거나, 신고 때 아직 퇴실 전이면 null
     */
    private ReportJudge.RoomAir roomAir(RoomRow room) {
        if (room.previousOut() == null || room.previousOut().isAfter(room.reportedAt())) {
            return null;
        }
        Instant from = room.previousOut().minus(AIR_BEFORE_OUT);
        if (from.isBefore(room.reportedAt().minus(AIR_MAX))) {
            from = room.reportedAt().minus(AIR_MAX);
        }
        // 인덱스 sensor_event_room_recent (room_id, kind, observed_at DESC)로 그 방 CO2 기록만 읽는다
        List<ReportJudge.AirPoint> points = jdbc.sql("""
                        SELECT (floor(extract(epoch FROM observed_at - :out) / 300) * 5)::int AS minute,
                               round(avg(ppm))::int AS ppm, round(avg(temperature_c), 1)::float8 AS temp
                        FROM sensor_event
                        WHERE room_id = :room AND kind = 'CO2' AND observed_at >= :from AND observed_at <= :at
                        GROUP BY 1 ORDER BY 1
                        """)
                .param("out", Timestamp.from(room.previousOut())).param("room", room.roomId())
                .param("from", Timestamp.from(from)).param("at", Timestamp.from(room.reportedAt()))
                .query((rs, n) -> new ReportJudge.AirPoint(rs.getInt("minute"), rs.getInt("ppm"),
                        rs.getObject("temp", Double.class)))
                .list();
        int reportMinute = (int) Duration.between(room.previousOut(), room.reportedAt()).toMinutes();
        return new ReportJudge.RoomAir(reportMinute, List.copyOf(points));
    }

    @Override
    public boolean lockPreviousSettled(long previousReservationId, boolean sawRecheck) {
        jdbc.sql("SELECT id FROM reservation WHERE id = :id FOR UPDATE").param("id", previousReservationId)
                .query((rs, n) -> rs.getLong(1)).optional();
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retrieve_pass WHERE reservation_id = :id AND first_used_at IS NOT NULL)
                            OR EXISTS (SELECT 1 FROM user_warning w JOIN report r ON r.id = w.report_id
                                       WHERE w.reservation_id = :id AND r.category = 'MESSY')
                            OR (NOT :sawRecheck AND EXISTS (SELECT 1 FROM tidy_check
                                                           WHERE reservation_id = :id AND phase = 'RECHECK'))
                        """)
                .param("id", previousReservationId).param("sawRecheck", sawRecheck)
                .query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public void record(long reportId, ReportJudge.Judgement j, ReportVerdict applied, Instant now) {
        String reasons = String.join(" ", j.reasons()).strip();
        jdbc.sql("""
                        INSERT INTO report_judgement (report_id, judge, prompt_version, verdict, confidence, applied,
                                                      reasons, evidence_used, created_at)
                        VALUES (:id, :judge, :prompt, :verdict, :confidence, :applied, :reasons, :used, :now)
                        """)
                .param("id", reportId).param("judge", j.judge()).param("prompt", j.promptVersion())
                .param("verdict", j.verdict().name()).param("confidence", j.confidence())
                .param("applied", applied.name())
                .param("reasons", reasons.length() > 1000 ? reasons.substring(0, 999) + "…" : reasons)
                .param("used", j.evidenceUsed().toArray(String[]::new)).param("now", Timestamp.from(now))
                .update();
    }

    private static String[] strings(Array array) throws SQLException {
        return array == null ? new String[0] : (String[]) array.getArray();
    }
}
