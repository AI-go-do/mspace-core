package kr.ac.chosun.mspace.access.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.access.application.AdminCheckInPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 관리자 테스트용 입실 확인이 읽는 users·reservation */
@Component
class JdbcAdminCheckInAdapter implements AdminCheckInPort {

    private final JdbcClient jdbc;

    JdbcAdminCheckInAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }

    @Override
    public Optional<Target> findOpen(long reservationId, Instant now) {
        return jdbc.sql("""
                        SELECT room_id, access_code, status, checkin_deadline FROM reservation
                        WHERE id = :id AND status IN ('BOOKED', 'IN_USE') AND access_code IS NOT NULL
                          AND end_at > :now
                        """)
                .param("id", reservationId)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new Target(rs.getInt("room_id"), rs.getString("access_code"),
                        "IN_USE".equals(rs.getString("status")), rs.getTimestamp("checkin_deadline").toInstant()))
                .optional();
    }

    @Override
    public Optional<String> status(long reservationId) {
        return jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", reservationId)
                .query((rs, n) -> rs.getString(1)).optional();
    }
}
