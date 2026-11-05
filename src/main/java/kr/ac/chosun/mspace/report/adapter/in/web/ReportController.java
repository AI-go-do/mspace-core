package kr.ac.chosun.mspace.report.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.ZonedDateTime;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.report.application.SubmitReportUseCase;
import kr.ac.chosun.mspace.report.application.SubmitReportUseCase.NewReport;
import kr.ac.chosun.mspace.report.application.SubmitReportUseCase.Submitted;
import kr.ac.chosun.mspace.report.domain.ReportCategory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 신고 API (FR-RPT-101) */
@RestController
class ReportController {

    private final SubmitReportUseCase reports;

    ReportController(SubmitReportUseCase reports) {
        this.reports = reports;
    }

    /**
     * 예: POST /api/reports {"roomId":2,"category":"NOISE","content":"벽 너머로 계속 소리 질러요","noiseConfirmed":true}
     * → 201 {"id":7,"roomId":2,"category":"NOISE","verdict":"OPEN","createdAt":"2026-11-12T14:20:05+09:00"}
     * 방·종류가 없거나 틀리거나, 소음인데 noiseConfirmed가 없으면 400, 지금 그 방·옆 방을 쓰는 예약자가 아니면 403
     */
    @PostMapping("/api/reports")
    @ResponseStatus(HttpStatus.CREATED)
    ReportResponse submit(LoginUser user, @RequestBody ReportRequest request) {
        if (request.roomId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "신고할 방(roomId)을 골라 주세요");
        }
        Submitted s = reports.submit(user.id(), new NewReport(request.roomId(), request.category(), request.content(),
                Boolean.TRUE.equals(request.noiseConfirmed())));
        return new ReportResponse(s.id(), s.roomId(), s.category(), s.verdict().name(),
                s.createdAt().atZone(SEOUL));
    }

    record ReportRequest(Integer roomId, ReportCategory category, String content, Boolean noiseConfirmed) {
    }

    record ReportResponse(long id, int roomId, ReportCategory category, String verdict, ZonedDateTime createdAt) {
    }
}
