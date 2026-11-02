package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 예약 저장. DB 제약 이름을 업무 오류로 바꾸는 일도 여기서 한다
 * (서비스는 "no_room_overlap" 같은 DB 단어를 몰라도 된다).
 */
@Component
class JdbcReservationAdapter implements ReservationCommandPort {

    /** DB 제약 이름 → 응답 오류. V1__init_schema.sql의 이름과 같아야 한다 */
    private static final Map<String, ErrorCode> CONSTRAINTS = Map.of(
            "no_room_overlap", ErrorCode.RESERVATION_TIME_TAKEN,
            "one_active_per_owner", ErrorCode.OWNER_HAS_ACTIVE_RESERVATION,
            "no_person_overlap", ErrorCode.PARTICIPANT_TIME_TAKEN);

    private final JdbcClient jdbc;

    JdbcReservationAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RoomCapacity> findActiveRoom(int roomId) {
        return jdbc.sql("SELECT id, name, min_people, max_people FROM rooms WHERE id = :id AND is_active")
                .param("id", roomId)
                .query((rs, n) -> new RoomCapacity(rs.getInt("id"), rs.getString("name"),
                        rs.getInt("min_people"), rs.getInt("max_people")))
                .optional();
    }

    @Override
    public Optional<String> findVerifiedStudentId(long userId) {
        return jdbc.sql("SELECT student_id FROM users WHERE id = :id AND verified_at IS NOT NULL")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    @Override
    public Map<String, Long> findVerifiedUserIds(Collection<String> studentIds) {
        Map<String, Long> result = new HashMap<>();
        if (studentIds.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT id, student_id FROM users WHERE student_id IN (:ids) AND verified_at IS NOT NULL")
                .param("ids", studentIds)
                .query(rs -> {
                    result.put(rs.getString("student_id"), rs.getLong("id"));
                });
        return result;
    }

    @Override
    public long insert(NewReservation r) {
        try {
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code)
                            VALUES (:room, :owner, :start, :end, :headcount, :code)
                            RETURNING id
                            """)
                    .param("room", r.roomId())
                    .param("owner", r.ownerId())
                    .param("start", Timestamp.from(r.startAt()))
                    .param("end", Timestamp.from(r.endAt()))
                    .param("headcount", r.participantIds().size())
                    .param("code", r.accessCode())
                    .query(Long.class)
                    .single();
            for (Long userId : r.participantIds()) {
                // 시간·활성 여부는 트리거(participant_copy_times)가 예약에서 복사한다. 여기 값은 자리만 채움
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, start_at, end_at)
                                VALUES (:rid, :uid, :start, :end)
                                """)
                        .param("rid", id).param("uid", userId)
                        .param("start", Timestamp.from(r.startAt()))
                        .param("end", Timestamp.from(r.endAt()))
                        .update();
            }
            // 인원 확인 트리거는 원래 커밋 때 돈다. 여기서 바로 돌려서, 실패하면 커밋 전에 이 메서드 안에서 오류가 나게 한다
            jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
            return id;
        } catch (DataAccessException e) {
            ErrorCode code = CONSTRAINTS.get(constraintName(e));
            if (code != null) {
                throw new BusinessException(code);
            }
            throw e;
        }
    }

    /** PostgreSQL이 알려 준 위반 제약 이름. 없으면 null */
    private static String constraintName(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException p) {
                ServerErrorMessage m = p.getServerErrorMessage();
                return m == null ? null : m.getConstraint();
            }
            if (t instanceof SQLException s && s.getNextException() instanceof PSQLException p) {
                ServerErrorMessage m = p.getServerErrorMessage();
                return m == null ? null : m.getConstraint();
            }
        }
        return null;
    }
}
