package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.reservation.application.ForceCheckoutPort;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 관리자 강제 퇴실. 보통 퇴실(JdbcReservationStatusAdapter)과 같은 조건부 UPDATE에 대표자 조건만 없다 */
@Component
class JdbcForceCheckoutAdapter implements ForceCheckoutPort {

    private final JdbcClient jdbc;

    JdbcForceCheckoutAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }

    @Override
    public Optional<String> forceCheckoutIfInUse(long reservationId, long adminId, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE reservation r
                        SET status = 'CHECKED_OUT', checked_out_at = :now, forced_by = :admin, forced_reason = :reason
                        FROM rooms rm
                        WHERE r.id = :id AND r.status = 'IN_USE' AND rm.id = r.room_id
                        RETURNING rm.name
                        """)
                .param("id", reservationId)
                .param("admin", adminId)
                .param("reason", reason)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> rs.getString(1)).optional();
    }

    @Override
    public List<Long> members(long reservationId) {
        return jdbc.sql("""
                        SELECT user_id FROM reservation_participant WHERE reservation_id = :id
                        ORDER BY is_owner DESC, user_id
                        """)
                .param("id", reservationId).query((rs, n) -> rs.getLong(1)).list();
    }

    @Override
    public Optional<ReservationStatus> status(long reservationId) {
        return jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", reservationId)
                .query((rs, n) -> rs.getString(1)).optional().map(ReservationStatus::valueOf);
    }
}
