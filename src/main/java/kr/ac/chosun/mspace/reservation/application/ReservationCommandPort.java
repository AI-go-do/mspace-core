package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.chosun.mspace.reservation.domain.CreatedVia;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;

/** 예약을 만들 때 필요한 DB 일. 구현은 adapter/out/persistence. */
public interface ReservationCommandPort {

    // TODO(member 모듈이 생기면): 아래 두 회원 조회는 member 모듈 application의 공개 창구(MemberQuery)로 옮긴다.
    //  지금은 member 모듈이 없어서 예약 어댑터가 users 테이블을 직접 읽는다 (방은 RoomQuery로 옮김)

    /** 학생 인증(verified_at)을 마친 회원의 학번 */
    Optional<String> findVerifiedStudentId(long userId);

    /** 회원 이름 (참여자 알림에 가려서 쓴다). 없으면 빈 값 */
    Optional<String> findName(long userId);

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
     * @param idempotencyKey  멱등 키. 없으면 null
     * @param instant         즉시 시작이면 true (is_instant: 노쇼 마감이 시작 + 10분이 되고, 길이 규칙이 다르다)
     * @param createdVia      어디서 만들었나 (created_via)
     * @param inUse           true면 처음부터 IN_USE(입실 시각 = 지금)로 넣는다. 연장하기만 쓴다
     * @param continuedFromId 연장이면 원래 예약 id, 아니면 null
     * @param relayOfferId    빈방 릴레이로 넘겨받은 자리를 받는 예약이면 그 넘김 id, 아니면 null
     * @param at              지금 (앱 Clock). 다른 사람에게 넘겨 둔 자리(릴레이)인지 볼 때 쓴다. null이면 DB 시각
     */
    record NewReservation(int roomId, long ownerId, Instant startAt, Instant endAt,
                          String accessCode, List<Long> participantIds, String idempotencyKey, boolean instant,
                          CreatedVia createdVia, boolean inUse, Long continuedFromId, Long relayOfferId,
                          Instant at) {

        /** 연장하기 */
        public NewReservation(int roomId, long ownerId, Instant startAt, Instant endAt,
                              String accessCode, List<Long> participantIds, String idempotencyKey, boolean instant,
                              CreatedVia createdVia, boolean inUse, Long continuedFromId) {
            this(roomId, ownerId, startAt, endAt, accessCode, participantIds, idempotencyKey, instant, createdVia,
                    inUse, continuedFromId, null, null);
        }

        /** 미리 예약·즉시 시작 */
        public NewReservation(int roomId, long ownerId, Instant startAt, Instant endAt,
                              String accessCode, List<Long> participantIds, String idempotencyKey, boolean instant,
                              CreatedVia createdVia) {
            this(roomId, ownerId, startAt, endAt, accessCode, participantIds, idempotencyKey, instant, createdVia,
                    false, null);
        }

        /** 미리 예약 */
        public NewReservation(int roomId, long ownerId, Instant startAt, Instant endAt,
                              String accessCode, List<Long> participantIds, String idempotencyKey) {
            this(roomId, ownerId, startAt, endAt, accessCode, participantIds, idempotencyKey, false, CreatedVia.WEB);
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
