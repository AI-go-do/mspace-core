package kr.ac.chosun.mspace.appeal.adapter.out.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.appeal.application.WarningAppealPort;
import kr.ac.chosun.mspace.appeal.application.WarningAppealUseCase.Appeal;
import kr.ac.chosun.mspace.appeal.application.WarningAppealUseCase.WarningItem;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcWarningAppealAdapter implements WarningAppealPort {

    /**
     * 경고 한 줄. 경고가 걸린 예약은 위반이면 경고의 예약, 허위 신고면 신고할 때 쓰던 예약 (docs api 6-1).
     * 신고 하나에 경고 하나(one_warning_per_report)라 report 조인은 한 줄
     */
    private static final String ITEM = """
            SELECT w.id, w.reason, p.category, p.room_id, p.source,
                   coalesce(w.reservation_id, p.reporter_reservation_id) AS reservation_id,
                   r.start_at, r.end_at, w.created_at,
                   a.status, a.text, a.created_at AS appealed_at, a.decided_at, a.answer
            FROM user_warning w
            JOIN report p ON p.id = w.report_id
            LEFT JOIN reservation r ON r.id = coalesce(w.reservation_id, p.reporter_reservation_id)
            LEFT JOIN warning_appeal a ON a.warning_id = w.id
            """;

    private final JdbcClient jdbc;

    JdbcWarningAppealAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<WarningItem> mine(long userId, Instant since, int appealableDays) {
        return jdbc.sql(ITEM + " WHERE w.user_id = :user AND w.created_at > :since ORDER BY w.created_at DESC, w.id DESC")
                .param("user", userId).param("since", Timestamp.from(since))
                .query((rs, n) -> item(rs, appealableDays)).list();
    }

    @Override
    public Optional<WarningItem> one(long userId, long warningId, int appealableDays) {
        return jdbc.sql(ITEM + " WHERE w.user_id = :user AND w.id = :id")
                .param("user", userId).param("id", warningId)
                .query((rs, n) -> item(rs, appealableDays)).optional();
    }

    private static WarningItem item(ResultSet rs, int appealableDays) throws SQLException {
        Instant created = rs.getTimestamp("created_at").toInstant();
        String status = rs.getString("status");
        Appeal appeal = status == null ? null : new Appeal(status, rs.getString("text"),
                rs.getTimestamp("appealed_at").toInstant(), instant(rs, "decided_at"), rs.getString("answer"));
        long rid = rs.getLong("reservation_id");
        Long reservation = rs.wasNull() ? null : rid;
        String reason = rs.getString("reason");
        String category = rs.getString("category");
        return new WarningItem(rs.getLong("id"), reason, category, rs.getInt("room_id"),
                reservation, instant(rs, "start_at"), instant(rs, "end_at"), created,
                why(reason, rs.getString("source"), category), created.plus(Duration.ofDays(appealableDays)), appeal);
    }

    /** 학생에게 보이는 경고 이유 한 줄 */
    static String why(String reason, String source, String category) {
        if ("FALSE_REPORT".equals(reason)) {
            return label(category) + " 신고가 허위로 판정됐어요";
        }
        if ("CAMERA".equals(source)) {
            return "퇴실 뒤 방 카메라가 다시 봤을 때도 두고 간 물건이 남아 있었어요";
        }
        return "이용하신 방의 " + label(category) + " 신고가 사실로 판정됐어요";
    }

    private static String label(String category) {
        return switch (category) {
            case "BROKEN" -> "비품 고장";
            case "WINDOW_OPEN" -> "창문 열림";
            case "MESSY" -> "어질러짐";
            case "NOISE" -> "소음";
            default -> category;
        };
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    @Override
    public Optional<Instant> lockMine(long userId, long warningId) {
        return jdbc.sql("SELECT created_at FROM user_warning WHERE id = :id AND user_id = :user FOR UPDATE")
                .param("id", warningId).param("user", userId)
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).optional();
    }

    @Override
    public boolean insertIfAbsent(long userId, long warningId, String text, List<String> evidence, Instant now) {
        return jdbc.sql("""
                        INSERT INTO warning_appeal (warning_id, user_id, text, evidence, created_at)
                        VALUES (:warning, :user, :text, :evidence, :now)
                        ON CONFLICT (warning_id) DO NOTHING
                        """)
                .param("warning", warningId).param("user", userId).param("text", text)
                .param("evidence", evidence.toArray(String[]::new)).param("now", Timestamp.from(now))
                .update() == 1;
    }

    @Override
    public List<Long> pending(int limit) {
        return jdbc.sql("SELECT id FROM warning_appeal WHERE status = 'PENDING' ORDER BY created_at, id LIMIT :n")
                .param("n", limit).query(Long.class).list();
    }

    @Override
    public Optional<WarningReviewer.Case> loadCase(long appealId) {
        // 사진은 경고가 걸린 예약 것. 카메라 경고면 그 신고를 만든 다시 확인(RECHECK) 결과를 같이 넘긴다
        return jdbc.sql("""
                        SELECT a.id, w.reason, p.source, p.category, p.content, a.text, a.evidence,
                               EXISTS (SELECT 1 FROM reservation_photo f
                                       WHERE f.reservation_id = coalesce(w.reservation_id, p.reporter_reservation_id)
                                         AND f.kind = 'CHECK_IN') AS check_in,
                               EXISTS (SELECT 1 FROM reservation_photo f
                                       WHERE f.reservation_id = coalesce(w.reservation_id, p.reporter_reservation_id)
                                         AND f.kind = 'CHECK_OUT') AS check_out,
                               t.items, t.confidences
                        FROM warning_appeal a
                        JOIN user_warning w ON w.id = a.warning_id
                        JOIN report p ON p.id = w.report_id
                        LEFT JOIN tidy_check t ON t.report_id = p.id AND t.phase = 'RECHECK'
                        WHERE a.id = :id AND a.status = 'PENDING'
                        """)
                .param("id", appealId)
                .query((rs, n) -> {
                    String[] items = strings(rs.getArray("items"));
                    Array conf = rs.getArray("confidences");
                    List<Double> confidences = new ArrayList<>();
                    if (conf != null) {
                        for (Float f : (Float[]) conf.getArray()) {
                            confidences.add(f == null ? null : f.doubleValue());
                        }
                    }
                    return new WarningReviewer.Case(rs.getLong("id"), rs.getString("reason"), rs.getString("source"),
                            rs.getString("category"), rs.getString("content"), rs.getString("text"),
                            Arrays.asList(strings(rs.getArray("evidence"))), rs.getBoolean("check_in"),
                            rs.getBoolean("check_out"), Arrays.asList(items), confidences);
                })
                .optional();
    }

    private static String[] strings(Array array) throws SQLException {
        return array == null ? new String[0] : (String[]) array.getArray();
    }

    @Override
    public Optional<Pending> lockPending(long appealId) {
        return jdbc.sql("""
                        SELECT a.id, a.user_id, a.warning_id, w.reason,
                               coalesce(w.reservation_id, p.reporter_reservation_id) AS reservation_id
                        FROM warning_appeal a
                        JOIN user_warning w ON w.id = a.warning_id
                        JOIN report p ON p.id = w.report_id
                        WHERE a.id = :id AND a.status = 'PENDING'
                        FOR UPDATE OF a
                        """)
                .param("id", appealId)
                .query((rs, n) -> {
                    long rid = rs.getLong("reservation_id");
                    Long reservation = rs.wasNull() ? null : rid;
                    return new Pending(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("warning_id"),
                            rs.getString("reason"), reservation);
                })
                .optional();
    }

    @Override
    public void decide(long appealId, WarningReviewer.Decision decision, String answer, WarningReviewer.Review review,
                       Instant now) {
        jdbc.sql("""
                        UPDATE warning_appeal
                        SET status = :status, decided_at = :now, answer = :answer, reviewer = :reviewer,
                            prompt_version = :prompt, confidence = :confidence, evidence_used = :used
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("status", decision.name()).param("now", Timestamp.from(now)).param("answer", answer)
                .param("reviewer", review.reviewer()).param("prompt", review.promptVersion())
                .param("confidence", (float) review.confidence())
                .param("used", review.evidenceUsed().toArray(String[]::new)).param("id", appealId)
                .update();
    }

    @Override
    public void withdraw(long warningId, Instant now) {
        jdbc.sql("UPDATE user_warning SET withdrawn_at = :now WHERE id = :id AND withdrawn_at IS NULL")
                .param("now", Timestamp.from(now)).param("id", warningId).update();
    }

    @Override
    public boolean liftSuspension(long userId, long warningId, String reason, int windowDays, Instant now) {
        boolean violation = "VIOLATION".equals(reason);
        String table = violation ? "user_suspension" : "report_suspension";
        String kind = violation ? " AND %s.reason = 'WARNINGS'" : "";
        // 그 경고를 센 정지: 정지를 건 경고 id 이하 + 앞 정지(풀리지 않은 것) 경계보다 뒤 + 정지 시작 windowDays 안에 받은 경고.
        // 끝을 지금으로 당긴다 (시작과 같으면 기간 CHECK에 걸리니 1ms 뒤). 기간만 줄어서 겹침 제약에는 걸리지 않는다
        String sql = """
                UPDATE %1$s s
                SET ends_at = GREATEST(CAST(:now AS timestamptz), s.starts_at + interval '1 millisecond'), lifted_at = :now
                FROM user_warning w
                WHERE w.id = :warning AND s.user_id = :user%2$s
                  AND s.lifted_at IS NULL AND s.ends_at > :now
                  AND s.warning_id >= w.id
                  AND w.created_at > s.starts_at - make_interval(days => :days)
                  AND w.id > coalesce((SELECT max(o.warning_id) FROM %1$s o
                                       WHERE o.user_id = s.user_id%3$s AND o.lifted_at IS NULL
                                         AND o.warning_id < s.warning_id), 0)
                """.formatted(table, kind.formatted("s"), kind.formatted("o"));
        return jdbc.sql(sql).param("now", Timestamp.from(now)).param("warning", warningId).param("user", userId)
                .param("days", windowDays).update() > 0;
    }
}
