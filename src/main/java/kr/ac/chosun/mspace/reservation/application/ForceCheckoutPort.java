package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;

/** 관리자 강제 퇴실 저장소. 구현: adapter/out/persistence */
public interface ForceCheckoutPort {

    /** 관리자(users.role = ADMIN)인지. 없는 회원이면 false */
    boolean isAdmin(long userId);

    /**
     * IN_USE일 때만 CHECKED_OUT으로 바꾸고 forced_by·forced_reason을 남긴다 (조건부 UPDATE 한 번).
     *
     * @return 바꿨으면 그 예약의 방 이름. 조건이 안 맞으면 빈 값
     */
    Optional<String> forceCheckoutIfInUse(long reservationId, long adminId, String reason, Instant now);

    /** 예약의 대표자·참여자 회원 id (알림 받을 사람) */
    List<Long> members(long reservationId);

    /** 없는 예약이면 빈 값 */
    Optional<ReservationStatus> status(long reservationId);
}
