package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 예약을 만들 때 필요한 DB 일. 구현은 adapter/out/persistence. */
public interface ReservationCommandPort {

    /** 사용 가능한(is_active) 방 */
    Optional<RoomCapacity> findActiveRoom(int roomId);

    /** 학생 인증(verified_at)을 마친 회원의 학번 */
    Optional<String> findVerifiedStudentId(long userId);

    /** 학번 → 회원 id. 학생 인증을 마친 회원만 */
    Map<String, Long> findVerifiedUserIds(Collection<String> studentIds);

    /**
     * 예약과 참여자를 저장한다. DB 제약에 걸리면 BusinessException(409)으로 바꿔서 던진다.
     * 호출하는 쪽 트랜잭션 안에서 불러야 한다 (인원 확인 트리거가 같은 트랜잭션을 본다).
     */
    long insert(NewReservation reservation);

    record RoomCapacity(int id, String name, int minPeople, int maxPeople) {
    }

    record NewReservation(int roomId, long ownerId, Instant startAt, Instant endAt,
                          String accessCode, List<Long> participantIds) {
    }
}
