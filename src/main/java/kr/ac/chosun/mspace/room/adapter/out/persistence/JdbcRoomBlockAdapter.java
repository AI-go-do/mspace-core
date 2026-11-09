package kr.ac.chosun.mspace.room.adapter.out.persistence;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.room.application.BlockReason;
import kr.ac.chosun.mspace.room.application.RoomBlockPort;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.Block;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.Conflict;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.NewBlock;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 기간 막기 저장 (V22 room_block) */
@Component
class JdbcRoomBlockAdapter implements RoomBlockPort {

    private static final String COLUMNS = "id, room_id, start_at, end_at, reason, memo, created_at, released_at";

    private final JdbcClient jdbc;

    JdbcRoomBlockAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }

    /** 예약 저장(JdbcReservationAdapter.lockInOrder)과 같은 번호: 방은 -방 id */
    @Override
    public void lockRoom(int roomId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", -(long) roomId).query((rs, n) -> 1).list();
    }

    @Override
    public Block insert(long adminId, NewBlock b, Instant now) {
        try {
            return jdbc.sql("""
                            INSERT INTO room_block (room_id, start_at, end_at, reason, memo, created_by, created_at)
                            VALUES (:room, :start, :end, :reason, :memo, :admin, :now)
                            RETURNING %s
                            """.formatted(COLUMNS))
                    .param("room", b.roomId())
                    .param("start", Timestamp.from(b.startAt()))
                    .param("end", Timestamp.from(b.endAt()))
                    .param("reason", b.reason().name())
                    .param("memo", b.memo(), Types.VARCHAR)
                    .param("admin", adminId)
                    .param("now", Timestamp.from(now))
                    .query(JdbcRoomBlockAdapter::block).single();
        } catch (DataIntegrityViolationException e) {
            // 같은 방의 풀리지 않은 막기와 겹침 (EXCLUDE no_block_overlap → 23P01)
            if ("no_block_overlap".equals(constraint(e))) {
                throw new BusinessException(ErrorCode.ROOM_BLOCK_OVERLAP);
            }
            throw e;
        }
    }

    @Override
    public List<Block> findEndingAfter(Instant from) {
        return jdbc.sql("SELECT %s FROM room_block WHERE end_at > :from ORDER BY start_at, id".formatted(COLUMNS))
                .param("from", Timestamp.from(from))
                .query(JdbcRoomBlockAdapter::block).list();
    }

    @Override
    public Optional<Block> find(long blockId) {
        return jdbc.sql("SELECT %s FROM room_block WHERE id = :id".formatted(COLUMNS))
                .param("id", blockId)
                .query(JdbcRoomBlockAdapter::block).optional();
    }

    @Override
    public boolean release(long blockId, long adminId, Instant now) {
        return jdbc.sql("""
                        UPDATE room_block SET released_at = :now, released_by = :admin
                        WHERE id = :id AND released_at IS NULL
                        """)
                .param("id", blockId).param("admin", adminId).param("now", Timestamp.from(now))
                .update() == 1;
    }

    @Override
    public List<Conflict> conflicts(int roomId, Instant startAt, Instant endAt, Instant now) {
        return jdbc.sql("""
                        SELECT id, room_id, start_at, end_at, headcount, status,
                               status = 'BOOKED' AND start_at > :now AS cancelable
                        FROM reservation
                        WHERE room_id = :room AND status IN ('BOOKED', 'IN_USE')
                          AND tstzrange(start_at, end_at) && tstzrange(:start, :end)
                        ORDER BY start_at, id
                        """)
                .param("room", roomId)
                .param("start", Timestamp.from(startAt))
                .param("end", Timestamp.from(endAt))
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new Conflict(rs.getLong("id"), rs.getInt("room_id"), at(rs, "start_at"),
                        at(rs, "end_at"), rs.getInt("headcount"), rs.getString("status"), rs.getBoolean("cancelable")))
                .list();
    }

    @Override
    public List<Long> cancelBooked(int roomId, Instant startAt, Instant endAt, Instant now) {
        return jdbc.sql("""
                        UPDATE reservation SET status = 'CANCELED', canceled_at = :now
                        WHERE room_id = :room AND status = 'BOOKED' AND start_at > :now
                          AND tstzrange(start_at, end_at) && tstzrange(:start, :end)
                        RETURNING id
                        """)
                .param("room", roomId)
                .param("start", Timestamp.from(startAt))
                .param("end", Timestamp.from(endAt))
                .param("now", Timestamp.from(now))
                .query((rs, n) -> rs.getLong(1)).list()
                .stream().sorted().toList();
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
    public String roomName(int roomId) {
        return jdbc.sql("SELECT name FROM rooms WHERE id = :id").param("id", roomId)
                .query((rs, n) -> rs.getString(1)).single();
    }

    /** PostgreSQL이 알려 준 위반 제약 이름. 없으면 null */
    private static String constraint(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException p && p.getServerErrorMessage() != null) {
                return p.getServerErrorMessage().getConstraint();
            }
        }
        return null;
    }

    private static Block block(ResultSet rs, int n) throws SQLException {
        return new Block(rs.getLong("id"), rs.getInt("room_id"), at(rs, "start_at"), at(rs, "end_at"),
                BlockReason.valueOf(rs.getString("reason")), rs.getString("memo"), at(rs, "created_at"),
                at(rs, "released_at"));
    }

    private static ZonedDateTime at(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().atZone(SEOUL);
    }
}
