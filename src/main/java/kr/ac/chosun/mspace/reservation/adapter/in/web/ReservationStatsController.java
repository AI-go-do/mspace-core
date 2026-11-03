package kr.ac.chosun.mspace.reservation.adapter.in.web;

import java.time.LocalDate;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.reservation.application.ReservationStatsUseCase;
import kr.ac.chosun.mspace.reservation.application.ReservationStatsUseCase.Counts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 예약 건수 API. 관리자(users.role = ADMIN)만 볼 수 있다 (11/3 지영 결정) */
@RestController
class ReservationStatsController {

    private final ReservationStatsUseCase stats;

    ReservationStatsController(ReservationStatsUseCase stats) {
        this.stats = stats;
    }

    /**
     * WEB/AGENT 예약 건수. 예약을 만든 날(서울) 기준, 취소·노쇼도 포함.
     * 예: GET /api/reservations/stats?from=2026-11-01&to=2026-11-30
     * → {"from":"2026-11-01","to":"2026-11-30","total":42,"web":35,"agent":7}
     * from·to를 빼면 처음부터·지금까지. 날짜 모양이 틀리거나 from이 to보다 늦으면 400, 관리자가 아니면 403
     */
    @GetMapping("/api/reservations/stats")
    Counts count(LoginUser user,
                 @RequestParam(required = false) LocalDate from,
                 @RequestParam(required = false) LocalDate to) {
        return stats.count(user.id(), from, to);
    }
}
