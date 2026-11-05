package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.ContinuationPort;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort;
import kr.ac.chosun.mspace.reservation.application.UsageChainPort;
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
class JdbcReservationAdapter implements ReservationCommandPort, ContinuationPort, UsageChainPort {

    /** DB 제약 이름 → 응답 오류. V1__init_schema.sql의 이름과 같아야 한다 */
    private static final Map<String, ErrorCode> CONSTRAINTS = Map.of(
            "no_room_overlap", ErrorCode.RESERVATION_TIME_TAKEN,
            "one_active_per_owner", ErrorCode.OWNER_HAS_ACTIVE_RESERVATION,
            "no_person_overlap", ErrorCode.PARTICIPANT_TIME_TAKEN,
            "one_reservation_per_key", ErrorCode.IDEMPOTENCY_KEY_REUSED,
            "one_active_code_per_room", ErrorCode.ACCESS_CODE_TAKEN,
            "one_continuation_per_reservation", ErrorCode.RESERVATION_EXTENSION_LIMIT);

    /**
     * 회원당 끝나지 않은 예약 1건(V13, FR-RSV-003). 대표자도 참여자 표에 들어가므로 걸린 사람이 대표자인지 참여자인지에 따라
     * 오류를 나눈다. 걸린 회원 id는 PostgreSQL 오류 상세에서 읽는다:
     * 유일 인덱스 "Key (user_id)=(7) already exists.", 겹침 제약 "Key (user_id, tstzrange(start_at, end_at))=(7, [...)) ..."
     */
    private static final String ONE_ACTIVE_PER_MEMBER = "one_active_per_member";

    private static final String NO_PERSON_OVERLAP = "no_person_overlap";

    private static final java.util.regex.Pattern KEY_USER_ID =
            java.util.regex.Pattern.compile("^Key \\(user_id(?:,[^=]*)?\\)=\\((\\d+)");

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
                                                     idempotency_key, is_instant, created_via,
                                                     status, checked_in_at, continued_from_id)
                            VALUES (:room, :owner, :start, :end, :headcount, :code, :key, :instant, :via,
                                    CASE WHEN :inUse THEN 'IN_USE' ELSE 'BOOKED' END,
                                    CASE WHEN :inUse THEN CAST(:start AS timestamptz) END, :from)
                            RETURNING id
                            """)
                    .param("inUse", r.inUse())
                    .param("from", r.continuedFromId(), java.sql.Types.BIGINT)
                    .param("room", r.roomId())
                    .param("owner", r.ownerId())
                    .param("start", Timestamp.from(r.startAt()))
                    .param("end", Timestamp.from(r.endAt()))
                    .param("instant", r.instant())
                    .param("via", r.createdVia().name())
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
            if (ONE_ACTIVE_PER_MEMBER.equals(constraint)) {
                throw new BusinessException(memberConflict(e, r.ownerId(), ErrorCode.PARTICIPANT_HAS_ACTIVE_RESERVATION));
            }
            // 참여자인 사람이 겹치는 시간에 대표자로 예약하면 사람 겹침이 먼저 걸린다. 대표자 본인이면 대표자 오류로 안내
            if (NO_PERSON_OVERLAP.equals(constraint)) {
                throw new BusinessException(memberConflict(e, r.ownerId(), ErrorCode.PARTICIPANT_TIME_TAKEN));
            }
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
                        SELECT id, room_id, start_at, end_at, status, headcount, access_code, is_instant
                        FROM reservation WHERE owner_id = :owner AND idempotency_key = :key
                        """)
                .param("owner", ownerId)
                .param("key", idempotencyKey)
                .query((rs, n) -> new KeyedReservation(rs.getLong("id"), rs.getInt("room_id"),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                        ReservationStatus.valueOf(rs.getString("status")), rs.getInt("headcount"),
                        rs.getString("access_code"), List.of(), rs.getBoolean("is_instant")))
                .optional()
                .map(k -> new KeyedReservation(k.id(), k.roomId(), k.startAt(), k.endAt(), k.status(), k.headcount(),
                        k.accessCode(), otherStudentIds(k.id()), k.instant()));
    }

    @Override
    public Optional<ContinueTarget> findContinueTarget(long reservationId) {
        return jdbc.sql("""
                        SELECT id, room_id, owner_id, start_at, end_at, status, continued_from_id, checked_in_at
                        FROM reservation WHERE id = :id
                        """)
                .param("id", reservationId)
                .query((rs, n) -> new ContinueTarget(rs.getLong("id"), rs.getInt("room_id"), rs.getLong("owner_id"),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                        ReservationStatus.valueOf(rs.getString("status")),
                        rs.getObject("continued_from_id", Long.class),
                        rs.getTimestamp("checked_in_at") == null ? null : rs.getTimestamp("checked_in_at").toInstant(),
                        List.of()))
                .optional()
                .map(t -> new ContinueTarget(t.id(), t.roomId(), t.ownerId(), t.startAt(), t.endAt(), t.status(),
                        t.continuedFromId(), t.checkedInAt(), jdbc.sql(
                                "SELECT user_id FROM reservation_participant WHERE reservation_id = :id ORDER BY user_id")
                        .param("id", t.id()).query(Long.class).list()));
    }

    /**
     * 거슬러 올라가기: 지금 시작 바로 앞에, 같은 대표자가 실제로 쓰고(퇴실·자동 퇴실) gap 안에 끝낸 예약이 있으면 그 예약으로 옮겨
     * 다시 본다. 시작 시각이 계속 줄어서 반드시 멈춘다. 실제로 끝난 시각은 일찍 퇴실했으면 퇴실 시각이다.
     * end_at 조건은 (owner_id, end_at) 인덱스를 타게 하려고 둔다 (실제 끝 ≤ end_at이라 결과는 같다)
     */
    @Override
    public int extensionsBefore(long ownerId, Instant startAt, Duration gap) {
        return jdbc.sql("""
                        WITH RECURSIVE chain(id, start_at, continued_from_id) AS (
                            SELECT NULL::bigint, :start::timestamptz, NULL::bigint
                            UNION ALL
                            SELECT p.id, p.start_at, p.continued_from_id
                            FROM chain c
                            CROSS JOIN LATERAL (
                                SELECT r.id, r.start_at, r.continued_from_id FROM reservation r
                                WHERE r.owner_id = :owner
                                  AND r.status IN ('CHECKED_OUT', 'AUTO_CHECKED_OUT', 'AWAY_RELEASED')
                                  AND r.end_at >= c.start_at - :gap::interval
                                  AND r.start_at < c.start_at
                                  AND least(r.end_at, r.checked_out_at) >= c.start_at - :gap::interval
                                ORDER BY r.start_at DESC LIMIT 1
                            ) p
                        )
                        SELECT count(*) FROM chain WHERE continued_from_id IS NOT NULL
                        """)
                .param("start", Timestamp.from(startAt))
                .param("owner", ownerId)
                .param("gap", gap.toMinutes() + " minutes")
                .query(Integer.class)
                .single();
    }

    @Override
    public Optional<Long> continuedFrom(long reservationId) {
        return jdbc.sql("SELECT continued_from_id FROM reservation WHERE id = :id AND continued_from_id IS NOT NULL")
                .param("id", reservationId)
                .query(Long.class)
                .optional();
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
        // 잠금을 무한정 기다리지 않게 상한을 건다. lock_timeout은 잠금 "하나"마다 적용되므로,
        // 방 → 참여자 순으로 여러 개를 잡을 때 매번 "남은 시간"만 다시 걸어 이번 시도 전체가 상한을 넘지 않게 한다.
        // 넘으면 PostgreSQL 55P03 → insert의 catch가 CannotAcquireLockException으로 바꿔서 서비스의 다시 하기 → 503
        long deadline = System.nanoTime() + lockTimeout.toNanos();
        lockWithin(-(long) r.roomId(), deadline);
        for (Long userId : r.participantIds().stream().sorted().toList()) {
            lockWithin(userId, deadline);
        }
    }

    /** 남은 시간을 이 트랜잭션의 lock_timeout으로 걸고(SET LOCAL과 같음, 커밋·롤백 때 풀림) 잠금을 잡는다 */
    private void lockWithin(long key, long deadline) {
        long leftMs = (deadline - System.nanoTime()) / 1_000_000;
        if (leftMs < 1) {
            // 0ms는 PostgreSQL에서 "무제한"이라 걸 수 없다. 시간을 다 썼으면 바로 잠금 실패로 끝낸다
            throw new CannotAcquireLockException("예약 잠금 대기 시간 초과");
        }
        jdbc.sql("SELECT set_config('lock_timeout', :ms, true)")
                .param("ms", leftMs + "ms")
                .query((rs, n) -> 1).list();
        lock(key);
    }

    private void lock(long key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", key).query((rs, n) -> 1).list();
    }

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

    /** 걸린 회원이 대표자 본인이면 OWNER_HAS_ACTIVE_RESERVATION, 아니면(또는 상세를 못 읽으면) 참여자 쪽 오류 */
    private static ErrorCode memberConflict(DataAccessException e, long ownerId, ErrorCode participantError) {
        String detail = serverMessage(e).map(ServerErrorMessage::getDetail).orElse(null);
        if (detail != null) {
            java.util.regex.Matcher m = KEY_USER_ID.matcher(detail);
            if (m.find() && Long.parseLong(m.group(1)) == ownerId) {
                return ErrorCode.OWNER_HAS_ACTIVE_RESERVATION;
            }
        }
        return participantError;
    }

    private static Optional<ServerErrorMessage> serverMessage(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException p) {
                return Optional.ofNullable(p.getServerErrorMessage());
            }
            if (t instanceof SQLException s && s.getNextException() instanceof PSQLException p) {
                return Optional.ofNullable(p.getServerErrorMessage());
            }
        }
        return Optional.empty();
    }

    /** PostgreSQL이 알려 준 위반 제약 이름. 없으면 null */
    private static String constraintName(DataAccessException e) {
        return serverMessage(e).map(ServerErrorMessage::getConstraint).orElse(null);
    }
}
