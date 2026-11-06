package kr.ac.chosun.mspace.admin.adapter.in.web;

import java.time.LocalDate;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase.UsageStats;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 이용 통계 API (FR-WEB-101 관리자 화면). 관리자만 */
@RestController
class UsageStatsController {

    private final UsageStatsUseCase stats;

    UsageStatsController(UsageStatsUseCase stats) {
        this.stats = stats;
    }

    /**
     * 방별·전체 이용 통계와 센서 값. 날짜는 서울 기준, to 포함. 둘 다 빼면 오늘까지 7일.
     * 예: GET /api/stats/usage?from=2026-11-01&amp;to=2026-11-30
     * 날짜가 틀리거나 from이 to보다 늦거나 92일 넘으면 400, 관리자가 아니면 403
     */
    @GetMapping("/api/stats/usage")
    UsageStats usage(LoginUser user,
                     @RequestParam(required = false) LocalDate from,
                     @RequestParam(required = false) LocalDate to) {
        return stats.usage(user.id(), from, to);
    }
}
