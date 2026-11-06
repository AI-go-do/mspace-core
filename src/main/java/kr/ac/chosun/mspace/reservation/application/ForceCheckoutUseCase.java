package kr.ac.chosun.mspace.reservation.application;

import kr.ac.chosun.mspace.reservation.application.CheckoutReservationUseCase.CheckedOut;

/** 관리자 강제 퇴실 입력 포트 (FR-RPT-104 퇴출). 구현: {@link ForceCheckoutService} */
public interface ForceCheckoutUseCase {

    /** 사유(reason)는 비워도 되고 200자까지 */
    int MAX_REASON = 200;

    /**
     * 관리자가 사용 중(IN_USE)인 예약을 바로 끝낸다. 대표자·참여자 모두에게 알림이 간다.
     *
     * @return 퇴실한 예약 id와 상태(CHECKED_OUT)
     */
    CheckedOut forceCheckout(long adminId, long reservationId, String reason);
}
