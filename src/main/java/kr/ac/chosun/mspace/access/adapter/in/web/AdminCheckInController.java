package kr.ac.chosun.mspace.access.adapter.in.web;

import kr.ac.chosun.mspace.access.application.AdminCheckInService;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 화면 테스트용 입실 확인 (11/13 지영). QR 대신 눌러 QR 입실과 같은 처리를 한다 */
@RestController
class AdminCheckInController {

    private final AdminCheckInService service;

    AdminCheckInController(AdminCheckInService service) {
        this.service = service;
    }

    /**
     * 예: POST /api/admin/reservations/12/check-in → 200 {"id":12,"roomId":1,"status":"IN_USE","result":"OPENED"}.
     * 꺼져 있으면 403 ADMIN_CHECK_IN_OFF, 관리자가 아니면 403 ADMIN_ONLY, 없는 예약 404,
     * 끝났거나 아직 이르거나 입실 마감이 지났으면 409 RESERVATION_NOT_CHECKABLE
     */
    @PostMapping("/api/admin/reservations/{id}/check-in")
    CheckInResponse checkIn(LoginUser user, @PathVariable long id) {
        int roomId = service.checkIn(user.id(), id);
        return new CheckInResponse(id, roomId, "IN_USE", "OPENED");
    }

    record CheckInResponse(long id, int roomId, String status, String result) {
    }
}
