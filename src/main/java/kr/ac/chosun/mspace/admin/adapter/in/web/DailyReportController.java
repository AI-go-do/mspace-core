package kr.ac.chosun.mspace.admin.adapter.in.web;

import java.time.LocalDate;
import kr.ac.chosun.mspace.admin.application.DailyReportUseCase;
import kr.ac.chosun.mspace.admin.application.DailyReportUseCase.DailyReport;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 하루 운영 보고 API (관제 화면, 11/12 지영 "core 다 AI화"). 관리자만 */
@RestController
class DailyReportController {

    private final DailyReportUseCase reports;

    DailyReportController(DailyReportUseCase reports) {
        this.reports = reports;
    }

    /**
     * 하루 숫자와 AI가 쓴 3–5줄 요약. 날짜는 서울 기준, 빼면 오늘.
     * 예: GET /api/admin/daily-report?date=2026-11-12
     * 미래 날짜·형식이 틀리면 400, 관리자가 아니면 403
     */
    @GetMapping("/api/admin/daily-report")
    DailyReport report(LoginUser user, @RequestParam(required = false) LocalDate date) {
        return reports.report(user.id(), date);
    }
}
