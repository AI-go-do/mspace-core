package kr.ac.chosun.mspace.report.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.report.application.ReportEvidenceUseCase;
import kr.ac.chosun.mspace.report.application.ReportEvidenceUseCase.Evidence;
import kr.ac.chosun.mspace.report.application.ReportEvidenceUseCase.Photo;
import kr.ac.chosun.mspace.report.application.ReportEvidenceUseCase.Tidy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 신고 판정용 증거 사진 API. 관리자만 */
@RestController
class ReportEvidenceController {

    private final ReportEvidenceUseCase evidence;

    ReportEvidenceController(ReportEvidenceUseCase evidence) {
        this.evidence = evidence;
    }

    /**
     * 직전 이용 예약의 퇴실 사진과 신고자 예약의 입실 사진. 사진이 없으면 exists false와 note에 이유.
     * 예: GET /api/reports/7/evidence → {"reportId":7,"previous":{"reservationId":11,…,"photo":{"kind":"CHECK_OUT",
     * "exists":true,"url":"/api/reservations/11/photos/CHECK_OUT",…}},"reporter":{…},"note":null}
     * previous.tidyCheck는 그 예약 뒤 방 카메라 정리 확인 결과(없으면 null).
     * 방 카메라 자동 신고는 reporter null, previous는 카메라가 본 그 예약. 관리자가 아니면 403, 없는 신고 404
     */
    @GetMapping("/api/reports/{id}/evidence")
    EvidenceResponse evidence(LoginUser user, @PathVariable long id) {
        Evidence e = evidence.evidence(user.id(), id);
        PreviousResponse previous = e.previous() == null ? null
                : new PreviousResponse(e.previous().reservationId(), seoul(e.previous().checkedInAt()),
                seoul(e.previous().checkedOutAt()), photo(e.previous().photo()), tidy(e.previous().tidyCheck()));
        SideResponse reporter = e.reporter() == null ? null
                : new SideResponse(e.reporter().reservationId(), photo(e.reporter().photo()));
        return new EvidenceResponse(e.reportId(), previous, reporter, e.note());
    }

    private static PhotoResponse photo(Photo p) {
        return new PhotoResponse(p.kind(), p.exists(), p.url(), seoul(p.uploadedAt()));
    }

    private static TidyResponse tidy(Tidy t) {
        return t == null ? null : new TidyResponse(t.result(), t.items(), t.leftItems(), seoul(t.observedAt()));
    }

    private static ZonedDateTime seoul(Instant t) {
        return t == null ? null : t.atZone(SEOUL);
    }

    record EvidenceResponse(long reportId, PreviousResponse previous, SideResponse reporter, String note) {
    }

    record PreviousResponse(long reservationId, ZonedDateTime checkedInAt, ZonedDateTime checkedOutAt,
                            PhotoResponse photo, TidyResponse tidyCheck) {
    }

    record TidyResponse(String result, List<String> items, List<String> leftItems, ZonedDateTime observedAt) {
    }

    record SideResponse(long reservationId, PhotoResponse photo) {
    }

    record PhotoResponse(String kind, boolean exists, String url, ZonedDateTime uploadedAt) {
    }
}
