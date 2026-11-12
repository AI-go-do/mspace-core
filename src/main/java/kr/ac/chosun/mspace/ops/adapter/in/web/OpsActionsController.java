package kr.ac.chosun.mspace.ops.adapter.in.web;

import java.time.LocalDate;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.ops.application.OpsAutoUseCase;
import kr.ac.chosun.mspace.ops.application.OpsAutoUseCase.OpsAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 "AI가 처리한 일" 중 자동 처리 5개 (11/12 지영). 관리자는 보기만 한다 */
@RestController
class OpsActionsController {

    private final OpsAutoUseCase ops;

    OpsActionsController(OpsAutoUseCase ops) {
        this.ops = ops;
    }

    /**
     * 그날(서울) AI가 처리한 일, 최근 것부터. 날짜를 빼면 오늘.
     * 예: GET /api/admin/ops-actions?date=2026-11-12. 관리자가 아니면 403
     */
    @GetMapping("/api/admin/ops-actions")
    List<OpsAction> actions(LoginUser user, @RequestParam(required = false) LocalDate date) {
        return ops.actions(user.id(), date);
    }
}
