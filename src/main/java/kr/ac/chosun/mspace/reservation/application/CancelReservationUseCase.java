package kr.ac.chosun.mspace.reservation.application;

import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;

/** 시작 전 예약 취소 입력 포트 (FR-RSV-007). 구현: {@link CancelReservationService} */
public interface CancelReservationUseCase {

    /**
     * 대표자가 시작 전 예약을 취소한다. 취소하면 방·참여자 시간이 바로 풀린다.
     *
     * @param reservationId 예약 id
     * @param userId        로그인 회원 id
     * @return 취소된 예약 id와 상태(CANCELED)
     */
    Canceled cancel(long reservationId, long userId);

    record Canceled(long id, ReservationStatus status) {
    }
}
