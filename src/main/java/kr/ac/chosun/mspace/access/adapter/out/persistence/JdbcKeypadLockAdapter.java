package kr.ac.chosun.mspace.access.adapter.out.persistence;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.access.application.KeypadLockPort;
import kr.ac.chosun.mspace.access.application.Verdict;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** keypad_lock, access_event 테이블 (V3) */
@Component
class JdbcKeypadLockAdapter implements KeypadLockPort {

    private final JdbcClient jdbc;

    JdbcKeypadLockAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LockState> lockRoom(int roomId) {
        return jdbc.sql("SELECT failed_count, locked_until FROM keypad_lock WHERE room_id = :room FOR UPDATE")
                .param("room", roomId)
                .query((rs, n) -> {
                    Timestamp until = rs.getTimestamp("locked_until");
                    return new LockState(rs.getInt("failed_count"), until == null ? null : until.toInstant());
                })
                .optional();
    }

    @Override
    public void saveLock(int roomId, int failedCount, Instant lockedUntil) {
        if (lockedUntil == null) {
            jdbc.sql("UPDATE keypad_lock SET failed_count = :n, locked_until = NULL WHERE room_id = :room")
                    .param("room", roomId).param("n", failedCount).update();
            return;
        }
        jdbc.sql("UPDATE keypad_lock SET failed_count = :n, locked_until = :until WHERE room_id = :room")
                .param("room", roomId).param("n", failedCount).param("until", Timestamp.from(lockedUntil)).update();
    }

    @Override
    public Optional<Verdict> findEvent(String eventId) {
        return jdbc.sql("SELECT result, reason, purpose FROM access_event WHERE event_id = :id")
                .param("id", eventId)
                .query((rs, n) -> new Verdict(rs.getString("result"), rs.getString("reason"), rs.getString("purpose")))
                .optional();
    }

    @Override
    public void saveEvent(String eventId, int roomId, String deviceId, Verdict verdict, Long reservationId) {
        jdbc.sql("""
                        INSERT INTO access_event (event_id, room_id, device_id, result, reason, purpose, reservation_id)
                        VALUES (:id, :room, :device, :result, :reason, :purpose, :rid)
                        """)
                .param("id", eventId)
                .param("room", roomId)
                .param("device", deviceId, Types.VARCHAR)
                .param("result", verdict.result())
                .param("reason", verdict.reason(), Types.VARCHAR)
                .param("purpose", verdict.purpose(), Types.VARCHAR)
                .param("rid", reservationId, Types.BIGINT)
                .update();
    }
}
