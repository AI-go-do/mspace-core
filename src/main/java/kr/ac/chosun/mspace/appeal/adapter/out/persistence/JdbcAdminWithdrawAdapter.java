package kr.ac.chosun.mspace.appeal.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.appeal.application.AdminWithdrawPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcAdminWithdrawAdapter implements AdminWithdrawPort {

    private final JdbcClient jdbc;

    JdbcAdminWithdrawAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }

    @Override
    public Optional<Warning> find(long warningId) {
        // 알림을 붙일 예약: 위반 경고는 경고 받은 예약, 허위 신고 경고는 신고한 사람의 예약 (철회 요청 판단과 같음)
        return jdbc.sql("""
                        SELECT w.id, w.user_id, w.reason, coalesce(w.reservation_id, p.reporter_reservation_id) AS reservation_id,
                               w.withdrawn_at
                        FROM user_warning w JOIN report p ON p.id = w.report_id
                        WHERE w.id = :id
                        """)
                .param("id", warningId)
                .query((rs, n) -> {
                    long rid = rs.getLong("reservation_id");
                    Long reservation = rs.wasNull() ? null : rid;
                    Timestamp at = rs.getTimestamp("withdrawn_at");
                    return new Warning(rs.getLong("id"), rs.getLong("user_id"), rs.getString("reason"), reservation,
                            at == null ? null : at.toInstant());
                })
                .optional();
    }

    @Override
    public Optional<Long> pendingAppealOf(long warningId) {
        return jdbc.sql("SELECT id FROM warning_appeal WHERE warning_id = :id AND status = 'PENDING'")
                .param("id", warningId).query(Long.class).optional();
    }

    @Override
    public boolean withdrawByAdmin(long warningId, long adminId, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE user_warning SET withdrawn_at = :now, withdrawn_by = :admin, withdraw_reason = :reason
                        WHERE id = :id AND withdrawn_at IS NULL
                        """)
                .param("now", Timestamp.from(now)).param("admin", adminId).param("reason", reason).param("id", warningId)
                .update() == 1;
    }
}
