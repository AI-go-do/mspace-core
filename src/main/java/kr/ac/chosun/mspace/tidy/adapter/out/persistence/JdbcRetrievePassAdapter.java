package kr.ac.chosun.mspace.tidy.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.tidy.application.RetrievePassPort;
import kr.ac.chosun.mspace.tidy.application.RetrievePassUseCase.Pass;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 표는 V27__retrieve_pass.sql. 트랜잭션은 서비스(issue)나 access 키패드 확인(use)이 연다 */
@Component
class JdbcRetrievePassAdapter implements RetrievePassPort {

    private final JdbcClient jdbc;

    JdbcRetrievePassAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Ended> lockMine(long reservationId, long userId) {
        return jdbc.sql("""
                        SELECT r.room_id, r.status, r.checked_out_at FROM reservation r
                        WHERE r.id = :id
                          AND EXISTS (SELECT 1 FROM reservation_participant p
                                      WHERE p.reservation_id = r.id AND p.user_id = :user)
                        FOR UPDATE OF r
                        """)
                .param("id", reservationId).param("user", userId)
                .query((rs, n) -> {
                    Timestamp out = rs.getTimestamp("checked_out_at");
                    return new Ended(rs.getInt("room_id"), rs.getString("status"),
                            out == null ? null : out.toInstant());
                })
                .optional();
    }

    @Override
    public boolean leftItems(long reservationId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM tidy_check
                                       WHERE reservation_id = :id AND phase = 'FIRST' AND result = 'LEFT_ITEMS')
                        """)
                .param("id", reservationId).query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public boolean used(long reservationId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retrieve_pass WHERE reservation_id = :id AND first_used_at IS NOT NULL)")
                .param("id", reservationId).query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public Optional<Pass> alive(long reservationId, Instant now) {
        return jdbc.sql("""
                        SELECT reservation_id, room_id, code, expires_at FROM retrieve_pass
                        WHERE reservation_id = :id AND expires_at > :now
                        ORDER BY expires_at DESC
                        LIMIT 1
                        """)
                .param("id", reservationId).param("now", Timestamp.from(now))
                .query((rs, n) -> new Pass(rs.getLong("reservation_id"), rs.getInt("room_id"), rs.getString("code"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    @Override
    public boolean codeTaken(int roomId, String code, Instant now) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM reservation
                                       WHERE room_id = :room AND access_code = :code AND status IN ('BOOKED', 'IN_USE'))
                            OR EXISTS (SELECT 1 FROM retrieve_pass
                                       WHERE room_id = :room AND code = :code AND expires_at > :now)
                        """)
                .param("room", roomId).param("code", code).param("now", Timestamp.from(now))
                .query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public void insert(Pass pass, long issuedBy, Instant issuedAt) {
        jdbc.sql("""
                        INSERT INTO retrieve_pass (reservation_id, room_id, issued_by, code, issued_at, expires_at)
                        VALUES (:rid, :room, :user, :code, :at, :until)
                        """)
                .param("rid", pass.reservationId()).param("room", pass.roomId()).param("user", issuedBy)
                .param("code", pass.code()).param("at", Timestamp.from(issuedAt))
                .param("until", Timestamp.from(pass.expiresAt()))
                .update();
    }

    @Override
    public Optional<Live> find(int roomId, String code, Instant now) {
        return jdbc.sql("""
                        SELECT id, reservation_id FROM retrieve_pass
                        WHERE room_id = :room AND code = :code AND expires_at > :now
                        ORDER BY expires_at DESC LIMIT 1
                        """)
                .param("room", roomId).param("code", code).param("now", Timestamp.from(now))
                .query((rs, n) -> new Live(rs.getLong(1), rs.getLong(2))).optional();
    }

    @Override
    public boolean lockReservation(long reservationId) {
        return jdbc.sql("SELECT id FROM reservation WHERE id = :rid FOR UPDATE")
                .param("rid", reservationId).query((rs, n) -> rs.getLong(1)).optional().isPresent();
    }

    @Override
    public boolean markUsed(long passId, Instant now) {
        return jdbc.sql("""
                        UPDATE retrieve_pass SET first_used_at = coalesce(first_used_at, :now)
                        WHERE id = :id AND expires_at > :now
                        """)
                .param("id", passId).param("now", Timestamp.from(now))
                .update() == 1;
    }
}
