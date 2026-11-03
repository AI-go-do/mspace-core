package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    record NewReservation(int roomId, long ownerId, Instant startAt, Instant endAt,
                          String accessCode, List<Long> participantIds) {
    }
}
