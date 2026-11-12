package kr.ac.chosun.mspace.appeal.adapter.in.web;

import kr.ac.chosun.mspace.appeal.application.AdminWithdrawUseCase;
import kr.ac.chosun.mspace.appeal.application.AdminWithdrawUseCase.Withdrawn;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 경고 철회 (11/12 지영). 관리자만 */
@RestController
class AdminWithdrawController {

    private final AdminWithdrawUseCase withdraw;

    AdminWithdrawController(AdminWithdrawUseCase withdraw) {
        this.withdraw = withdraw;
    }

    /**
     * 경고 하나를 철회한다. 그 경고로 걸린 정지도 풀고, 학생에게 알리고, 대기 중인 철회 요청은 철회로 닫는다.
     * 예: POST /api/admin/warnings/12/withdraw {"reason": "카메라 오인으로 확인됨"}.
     * 관리자가 아니면 403 ADMIN_ONLY, 경고가 없으면 404, 이미 철회됐으면 409 WARNING_ALREADY_WITHDRAWN, 이유가 비거나 200자 넘으면 400
     */
    @PostMapping("/api/admin/warnings/{id}/withdraw")
    Withdrawn withdraw(LoginUser user, @PathVariable long id, @RequestBody WithdrawRequest request) {
        return withdraw.withdraw(user.id(), id, request.reason());
    }

    record WithdrawRequest(String reason) {
    }
}
