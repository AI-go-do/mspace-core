package kr.ac.chosun.mspace.admin.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.admin.application.PenaltyListPort;
import kr.ac.chosun.mspace.admin.application.PenaltyListUseCase.Member;
import kr.ac.chosun.mspace.admin.application.PenaltyListUseCase.Suspension;
import kr.ac.chosun.mspace.admin.application.PenaltyListUseCase.Warning;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcPenaltyListAdapter implements PenaltyListPort {

    private final JdbcClient jdbc;

    JdbcPenaltyListAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }

    @Override
    public List<Warning> warnings(Instant since) {
        return jdbc.sql("""
                        SELECT w.id, w.reason, w.report_id, w.reservation_id, w.created_at,
                               u.id AS user_id, u.student_id, u.name
                        FROM user_warning w JOIN users u ON u.id = w.user_id
                        WHERE w.created_at > :since
                        ORDER BY w.created_at DESC, w.id DESC
                        """)
                .param("since", Timestamp.from(since))
                // reservation_id는 허위 신고 경고면 NULL. wasNull()은 마지막에 읽은 칸만 보니 getObject로 바로 읽는다 (CodeRabbit)
                .query((rs, n) -> new Warning(rs.getLong("id"), member(rs), rs.getString("reason"), rs.getLong("report_id"),
                        rs.getObject("reservation_id", Long.class), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public List<Suspension> suspensions(Instant since, Instant now) {
        return jdbc.sql("""
                        SELECT 'USE' AS scope, s.id, s.reason, s.starts_at, s.ends_at, u.id AS user_id, u.student_id, u.name
                        FROM user_suspension s JOIN users u ON u.id = s.user_id
                        WHERE s.ends_at > :since
                        UNION ALL
                        SELECT 'REPORT', s.id, 'FALSE_REPORT', s.starts_at, s.ends_at, u.id, u.student_id, u.name
                        FROM report_suspension s JOIN users u ON u.id = s.user_id
                        WHERE s.ends_at > :since
                        ORDER BY starts_at DESC, id DESC
                        """)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> {
                    Instant starts = rs.getTimestamp("starts_at").toInstant();
                    Instant ends = rs.getTimestamp("ends_at").toInstant();
                    return new Suspension(rs.getString("scope"), rs.getLong("id"), member(rs), rs.getString("reason"),
                            starts, ends, !now.isBefore(starts) && now.isBefore(ends));
                })
                .list();
    }

    private static Member member(ResultSet rs) throws SQLException {
        return new Member(rs.getLong("user_id"), rs.getString("student_id"), rs.getString("name"));
    }
}
