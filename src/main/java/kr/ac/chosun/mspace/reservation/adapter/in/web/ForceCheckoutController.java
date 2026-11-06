package kr.ac.chosun.mspace.reservation.adapter.in.web;

import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.reservation.application.CheckoutReservationUseCase.CheckedOut;
import kr.ac.chosun.mspace.reservation.application.ForceCheckoutUseCase;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 강제 퇴실 API (FR-RPT-104 퇴출) */
@RestController
class ForceCheckoutController {

    private final ForceCheckoutUseCase forced;

    ForceCheckoutController(ForceCheckoutUseCase forced) {
        this.forced = forced;
    }

    /**
     * 관리자가 사용 중인 예약을 바로 끝낸다. 본문은 빼도 된다.
     * 예: POST /api/reservations/12/force-checkout {"reason":"신고 확인, 소음 계속"} → {"id":12,"status":"CHECKED_OUT"}
     * 사유 200자 넘으면 400, 관리자가 아니면 403, 없는 예약 404, 사용 중이 아니면 409
     */
    @PostMapping("/api/reservations/{id}/force-checkout")
    StatusChangeResponse forceCheckout(LoginUser user, @PathVariable long id,
                                       @RequestBody(required = false) ForceCheckoutRequest request) {
        CheckedOut c = forced.forceCheckout(user.id(), id, request == null ? null : request.reason());
        return new StatusChangeResponse(c.id(), c.status().name());
    }

    record ForceCheckoutRequest(String reason) {
    }
}
