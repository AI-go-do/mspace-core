package kr.ac.chosun.mspace.access.application;

import java.time.Instant;
import java.util.Optional;

/** 관리자 테스트용 입실 확인이 읽는 것. 구현은 adapter/out/persistence */
public interface AdminCheckInPort {

    boolean isAdmin(long userId);

    /** now에 아직 끝나지 않은(BOOKED·IN_USE) 예약의 방·입실 번호. 없거나 끝난 예약이면 빈 값 */
    Optional<Target> findOpen(long reservationId, Instant now);

    /** 지금 상태. 없는 예약이면 빈 값 */
    Optional<String> status(long reservationId);

    record Target(int roomId, String accessCode, boolean checkedIn, Instant checkinDeadline) {
    }
}
