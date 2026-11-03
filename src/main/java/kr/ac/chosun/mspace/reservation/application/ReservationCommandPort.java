package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;

/** 예약을 만들 때 필요한 DB 일. 구현은 adapter/out/persistence. */
public interface ReservationCommandPort {

    // TODO(member 모듈이 생기면): 아래 두 회원 조회는 member 모듈 application의 공개 창구(MemberQuery)로 옮긴다.
    //  지금은 member 모듈이 없어서 예약 어댑터가 users 테이블을 직접 읽는다 (방은 RoomQuery로 옮김)

    /** 학생 인증(verified_at)을 마친 회원의 학번 */
    Optional<String> findVerifiedStudentId(long userId);

    /** 학번 → 회원 id. 학생 인증을 마친 회원만 */
    Map<String, Long> findVerifiedUserIds(Collection<String> studentIds);

    /**
     * 예약과 참여자를 저장한다. DB 제약에 걸리면 BusinessException(409)으로 바꿔서 던진다.
     * 호출하는 쪽 트랜잭션 안에서 불러야 한다 (인원 확인 트리거가 같은 트랜잭션을 본다).
     */
    long insert(NewReservation reservation);

    /**
     * 이 대표자가 이 멱등 키로 만든 예약. 없으면 빈 값
     */
    Optional<KeyedReservation> findByIdempotencyKey(long ownerId, String idempotencyKey);

    /**
     * @param idempotencyKey 멱등 키. 없으면 null
     * @param instant        즉시 시작이면 true (is_instant: 노쇼 마감이 시작 + 10분이 되고, 길이 규칙이 다르다)
     */
    record NewReservation(int roomId, long ownerId, Instant startAt, Instant endAt,
                          String accessCode, List<Long> participantIds, String idempotencyKey, boolean instant) {

        /** 미리 예약 */
        public NewReservation(int roomId, long ownerId, Instant startAt, Instant endAt,
                              String accessCode, List<Long> participantIds, String idempotencyKey) {
            this(roomId, ownerId, startAt, endAt, accessCode, participantIds, idempotencyKey, false);
        }
    }

    /**
     * 멱등 키로 찾은 예약. 다시 온 요청과 내용이 같은지 비교하려고 참여자 학번(대표자 제외)과
     * 종류(instant: 지금 바로 쓰기인지)도 담는다
     */
    record KeyedReservation(long id, int roomId, Instant startAt, Instant endAt, ReservationStatus status,
                            int headcount, String accessCode, List<String> participantStudentIds, boolean instant) {
    }
}
