package kr.ac.chosun.mspace.report.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.report.application.JudgeReportUseCase.ReportItem;
import kr.ac.chosun.mspace.report.application.ReportJudgePort;
import kr.ac.chosun.mspace.report.application.ReportPort;
import kr.ac.chosun.mspace.report.domain.ReportCategory;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 신고 저장. 권한 판단은 V1__init_schema.sql 끝 "8-1. 신고 권한"과 같다 */
@Component
class JdbcReportAdapter implements ReportPort, ReportJudgePort {

    private static final String CONTENT = "content";
    private static final String COLUMNS =
            "id, room_id, category, content, verdict, source, reporter_reservation_id, created_at, judged_at";

    private final JdbcClient jdbc;

    JdbcReportAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> findBasisReservation(long userId, int roomId) {
        // 참여자 줄에는 대표자도 들어 있다(is_owner). 한 사람은 같은 시간에 두 방에 못 들어가서(no_person_overlap) 보통 한 줄이다
        return jdbc.sql("""
                        SELECT r.id
                        FROM reservation r
                        JOIN reservation_participant p ON p.reservation_id = r.id AND p.user_id = :user
                        WHERE r.status = 'IN_USE'
                          AND (r.room_id = :room
                               OR EXISTS (SELECT 1 FROM room_adjacency a
                                          WHERE a.room_id = r.room_id AND a.adjacent_room_id = :room))
                        ORDER BY (r.room_id = :room) DESC, r.id
                        LIMIT 1
                        """)
                .param("user", userId)
                .param("room", roomId)
                .query(Long.class)
                .optional();
    }

    @Override
    public boolean roomExists(int roomId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM rooms WHERE id = :room)").param("room", roomId)
                .query(Boolean.class).single();
    }

    @Override
    public Saved save(int roomId, long reporterId, long basisReservationId, ReportCategory category, String content) {
        return jdbc.sql("""
                        INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category, content)
                        VALUES (:room, :reporter, :basis, :category, :content)
                        RETURNING id, created_at
                        """)
                .param("room", roomId)
                .param("reporter", reporterId)
                .param("basis", basisReservationId)
                .param("category", category.name())
                .param(CONTENT, content)
                .query((rs, n) -> new Saved(rs.getLong("id"), rs.getTimestamp("created_at").toInstant()))
                .single();
    }

    @Override
    public long saveCamera(int roomId, long targetReservationId, String content) {
        return jdbc.sql("""
                        INSERT INTO report (room_id, source, target_reservation_id, category, content)
                        VALUES (:room, 'CAMERA', :target, 'MESSY', :content)
                        RETURNING id
                        """)
                .param("room", roomId).param("target", targetReservationId).param(CONTENT, content)
                .query((rs, n) -> rs.getLong(1)).single();
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query(Boolean.class).optional().orElse(false);
    }

    @Override
    public List<ReportItem> list(ReportVerdict verdict) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM report"
                        + " WHERE CAST(:verdict AS varchar) IS NULL OR verdict = :verdict ORDER BY created_at DESC, id DESC")
                .param("verdict", verdict == null ? null : verdict.name())
                .query(JdbcReportAdapter::item)
                .list();
    }

    @Override
    public Optional<ReportItem> find(long reportId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM report WHERE id = :id").param("id", reportId)
                .query(JdbcReportAdapter::item).optional();
    }

    @Override
    public Optional<ReportItem> judgeIfOpen(long reportId, long adminId, ReportVerdict verdict) {
        return jdbc.sql("UPDATE report SET verdict = :verdict, judged_by = :admin, judged_at = now()"
                        + " WHERE id = :id AND verdict = 'OPEN' AND category <> 'NOISE' RETURNING " + COLUMNS)
                .param("verdict", verdict.name())
                .param("admin", adminId)
                .param("id", reportId)
                .query(JdbcReportAdapter::item)
                .optional();
    }

    private static ReportItem item(ResultSet rs, int n) throws SQLException {
        Timestamp judged = rs.getTimestamp("judged_at");
        return new ReportItem(rs.getLong("id"), rs.getInt("room_id"), ReportCategory.valueOf(rs.getString("category")),
                rs.getString(CONTENT), ReportVerdict.valueOf(rs.getString("verdict")),
                rs.getString("source"), rs.getObject("reporter_reservation_id", Long.class),
                rs.getTimestamp("created_at").toInstant(),
                judged == null ? null : judged.toInstant());
    }
}
