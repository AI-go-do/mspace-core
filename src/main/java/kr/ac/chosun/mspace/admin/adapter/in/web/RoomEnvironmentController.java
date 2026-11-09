package kr.ac.chosun.mspace.admin.adapter.in.web;

import kr.ac.chosun.mspace.admin.application.RoomEnvironmentUseCase;
import kr.ac.chosun.mspace.admin.application.RoomEnvironmentUseCase.RoomsEnvironment;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 "지금" 탭 (Q-21). 관리자만 */
@RestController
class RoomEnvironmentController {

    private final RoomEnvironmentUseCase environment;

    RoomEnvironmentController(RoomEnvironmentUseCase environment) {
        this.environment = environment;
    }

    /**
     * 방별 지금 CO2·온도·습도·재실 + 지난 minutes분(기본 60, 최대 180) 1분 값.
     * 예: GET /api/admin/rooms/environment?minutes=60. 관리자가 아니면 403 ADMIN_ONLY, minutes 범위 밖이면 400
     */
    @GetMapping("/api/admin/rooms/environment")
    RoomsEnvironment environment(LoginUser user, @RequestParam(required = false) Integer minutes) {
        return environment.environment(user.id(), minutes);
    }
}
