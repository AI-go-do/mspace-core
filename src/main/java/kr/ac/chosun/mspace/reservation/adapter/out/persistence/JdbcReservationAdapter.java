package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
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
            "no_person_overlap", ErrorCode.PARTICIPANT_TIME_TAKEN,
            "one_reservation_per_key", ErrorCode.IDEMPOTENCY_KEY_REUSED,
            "one_active_code_per_room", ErrorCode.ACCESS_CODE_TAKEN);

    private final JdbcClient jdbc;

    private final Duration lockTimeout;

    JdbcReservationAdapter(JdbcClient jdbc, @Value("${mspace.reservation.lock-timeout:3s}") Duration lockTimeout) {
        // PostgreSQL은 lock_timeout 0을 "무제한 대기"로 본다. 1ms 미만(0·음수·500us 등)은 시작할 때 막는다
        if (lockTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("mspace.reservation.lock-timeout은 1ms 이상이어야 해요: " + lockTimeout);
        }
        this.jdbc = jdbc;
        this.lockTimeout = lockTimeout;
    }

    /** 학생 인증을 마친 회원의 학번. 인증 전이거나 없는 회원이면 빈 값 */
    @Override
    public Optional<String> findVerifiedStudentId(long userId) {
        return jdbc.sql("SELECT student_id FROM users WHERE id = :id AND verified_at IS NOT NULL")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    /**
     * 학번 목록을 회원 id로 바꾼다. 인증을 마친 회원만 담기므로, 결과 크기가 입력보다 작으면 못 찾은 학번이 있다는 뜻이다.
     *
     * @param studentIds 참여자 학번 (비어 있으면 DB를 부르지 않는다)
     * @return 학번 → 회원 id
     */
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

    /**
     * 예약 한 줄과 참여자 줄들을 저장하고, 미뤄 둔 제약·트리거를 바로 확인한다.
     *
     * @param r 저장할 예약 (참여자 id에 대표자 포함)
     * @return 새 예약 id
     * @throws BusinessException 겹침·대표자 1건·같은 사람 두 방 제약에 걸리면 409
     */
    @Override
    public long insert(NewReservation r) {
        try {
            lockInOrder(r);
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code,
                                                     idempotency_key)
                            VALUES (:room, :owner, :start, :end, :headcount, :code, :key)
                            RETURNING id
                            """)
                    .param("room", r.roomId())
                    .param("owner", r.ownerId())
                    .param("start", Timestamp.from(r.startAt()))
                    .param("end", Timestamp.from(r.endAt()))
                    .param("headcount", r.participantIds().size())
                    .param("code", r.accessCode())
                    .param("key", r.idempotencyKey(), java.sql.Types.VARCHAR)
                    .query(Long.class)
                    .single();
            // 참여자는 id 순서로 넣는다. 두 예약이 같은 사람들을 다른 순서로 넣으면
            // 서로 상대가 넣은 줄을 기다리는 교착(deadlock, 40P01 → 500)이 생길 수 있어서,
            // 순서를 맞춰 한쪽이 기다렸다가 no_person_overlap(409)으로 끝나게 한다
            for (Long userId : r.participantIds().stream().sorted().toList()) {
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
            // 잠금 대기 상한(lock_timeout)을 넘음. Spring이 55P03을 분류하지 못해서 직접 잠금 실패로 바꾼다
            // → 서비스가 교착(40P01)과 똑같이 다시 하고, 3번 다 넘으면 503(RESERVATION_BUSY)
            if (LOCK_NOT_AVAILABLE.equals(sqlState(e))) {
                throw new CannotAcquireLockException("예약 잠금 대기 시간 초과", e);
            }
            // Map.of는 get(null)에서 NullPointerException → 제약 이름이 없으면(연결 끊김 등) 원래 오류를 그대로 던진다
            String constraint = constraintName(e);
            ErrorCode code = constraint == null ? null : CONSTRAINTS.get(constraint);
            if (code != null) {
                throw new BusinessException(code);
            }
            throw e;
        }
    }

    /** 멱등 키로 찾기. 제약 one_reservation_per_key(owner_id, idempotency_key)의 인덱스를 탄다 */
    @Override
    public Optional<KeyedReservation> findByIdempotencyKey(long ownerId, String idempotencyKey) {
        return jdbc.sql("""
                        SELECT id, room_id, start_at, end_at, status, headcount, access_code
                        FROM reservation WHERE owner_id = :owner AND idempotency_key = :key
                        """)
                .param("owner", ownerId)
                .param("key", idempotencyKey)
                .query((rs, n) -> new KeyedReservation(rs.getLong("id"), rs.getInt("room_id"),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                        ReservationStatus.valueOf(rs.getString("status")), rs.getInt("headcount"),
                        rs.getString("access_code"), List.of()))
                .optional()
                .map(k -> new KeyedReservation(k.id(), k.roomId(), k.startAt(), k.endAt(), k.status(), k.headcount(),
                        k.accessCode(), otherStudentIds(k.id())));
    }

    /** 예약의 참여자 학번 (대표자 제외) */
    private List<String> otherStudentIds(long reservationId) {
        return jdbc.sql("""
                        SELECT u.student_id FROM reservation_participant p JOIN users u ON u.id = p.user_id
                        WHERE p.reservation_id = :id AND NOT p.is_owner
                        """)
                .param("id", reservationId)
                .query(String.class)
                .list();
    }

    /**
     * 줄 세우기 잠금. 저장하기 전에 "방 → 사람(id 작은 순)" 순서로 트랜잭션 잠금을 잡는다.
     * <p>
     * 왜: 겹칠 수 있는 요청(같은 방, 같은 사람)이 동시에 저장되면 PostgreSQL 겹침(EXCLUDE) 검사끼리 서로 기다리는
     * 교착(40P01)이 난다. 같은 사람을 넣은 100팀이 서로 다른 방에 동시에 예약하면 69건이 500이었다.
     * 같은 번호를 잠그는 요청끼리는 한 줄로 서서 하나씩 저장되므로, 뒤 요청은 앞 예약을 보고 바로 409로 끝난다.
     * 모든 요청이 같은 순서로 잠그니 잠금끼리도 엇갈리지 않는다.
     * <p>
     * pg_advisory_xact_lock(번호): 테이블이 아니라 bigint 번호 하나만 잠그고, 커밋·롤백 때 저절로 풀린다.
     * 방은 음수(-방 id), 회원은 양수(회원 id, bigint 그대로)로 써서 둘이 섞이지 않게 한다.
     * 회원에는 대표자도 들어 있어서 대표자 1건 규칙도 같이 줄 선다.
     * 중복을 막는 건 여전히 DB 제약이고, 이 잠금은 교착만 없앤다.
     */
    private void lockInOrder(NewReservation r) {
        // 잠금을 무한정 기다리지 않게 이 트랜잭션에만 상한을 건다 (SET LOCAL과 같음, 커밋·롤백 때 풀림).
        // 넘으면 PostgreSQL 55P03 → Spring이 PessimisticLockingFailureException 계열로 바꿔서 서비스의 다시 하기 → 503
        jdbc.sql("SELECT set_config('lock_timeout', :ms, true)")
                .param("ms", lockTimeout.toMillis() + "ms")
                .query((rs, n) -> 1).list();
        lock(-(long) r.roomId());
        for (Long userId : r.participantIds().stream().sorted().toList()) {
            lock(userId);
        }
    }

    /** 번호 하나를 이 트랜잭션이 끝날 때까지 잠근다. 다른 트랜잭션이 쥐고 있으면 풀릴 때까지 기다린다 */
    private void lock(long key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", key).query((rs, n) -> 1).list();
    }

    /** PostgreSQL이 알려 준 위반 제약 이름. 없으면 null */
    /** PostgreSQL 오류 코드: 잠금을 기다리다 lock_timeout을 넘음 */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private static String sqlState(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getSQLState() != null) {
                return s.getSQLState();
            }
        }
        return null;
    }

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
