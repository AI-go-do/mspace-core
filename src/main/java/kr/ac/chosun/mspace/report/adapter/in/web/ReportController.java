package kr.ac.chosun.mspace.report.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.report.application.JudgeReportUseCase;
import kr.ac.chosun.mspace.report.application.JudgeReportUseCase.ReportItem;
import kr.ac.chosun.mspace.report.application.SubmitReportUseCase;
import kr.ac.chosun.mspace.report.application.SubmitReportUseCase.NewReport;
import kr.ac.chosun.mspace.report.application.SubmitReportUseCase.Submitted;
import kr.ac.chosun.mspace.report.domain.ReportCategory;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 신고 API (FR-RPT-101), 관리자 목록·판정 (FR-RPT-103) */
@RestController
class ReportController {

    private final SubmitReportUseCase reports;
    private final JudgeReportUseCase judges;

    ReportController(SubmitReportUseCase reports, JudgeReportUseCase judges) {
        this.reports = reports;
        this.judges = judges;
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
    /**
     * 관리자 신고 목록. 최근 신고가 위. 예: GET /api/reports?verdict=OPEN (빼면 전부). 관리자가 아니면 403
     */
    @GetMapping("/api/reports")
    List<ReportDetail> list(LoginUser user, @RequestParam(required = false) ReportVerdict verdict) {
        return judges.list(user.id(), verdict).stream().map(ReportDetail::from).toList();
    }

    /**
     * 관리자 판정. 예: POST /api/reports/7/judge {"verdict":"FALSE"} → 200, 판정이 반영된 신고.
     * 판정 값이 틀리면 400, 관리자가 아니면 403, 없는 신고 404, 소음이거나 이미 판정했으면 409
     */
    @PostMapping("/api/reports/{id}/judge")
    ReportDetail judge(LoginUser user, @PathVariable long id, @RequestBody JudgeRequest request) {
        return ReportDetail.from(judges.judge(user.id(), id, request.verdict()));
    }

    record JudgeRequest(ReportVerdict verdict) {
    }

    record ReportDetail(long id, int roomId, ReportCategory category, String content, String verdict,
                        long reporterReservationId, ZonedDateTime createdAt, ZonedDateTime judgedAt) {

        static ReportDetail from(ReportItem r) {
            return new ReportDetail(r.id(), r.roomId(), r.category(), r.content(), r.verdict().name(),
                    r.reporterReservationId(), r.createdAt().atZone(SEOUL),
                    r.judgedAt() == null ? null : r.judgedAt().atZone(SEOUL));
        }
    }
}
