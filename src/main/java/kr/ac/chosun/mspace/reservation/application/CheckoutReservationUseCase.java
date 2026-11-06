package kr.ac.chosun.mspace.reservation.application;

import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;

/** 퇴실 입력 포트 (FR-RSV-008). 구현: {@link CheckoutReservationService} */
public interface CheckoutReservationUseCase {

    /**
     * 대표자가 사용 중(IN_USE)인 예약을 끝낸다. 방·참여자 시간이 바로 풀린다.
     *
     * @return 퇴실한 예약 id와 상태(CHECKED_OUT)
     */
    CheckedOut checkout(long reservationId, long userId);

    record CheckedOut(long id, ReservationStatus status) {
    }
}
