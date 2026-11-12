package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.report.application.JudgeReportUseCase.ReportItem;
import kr.ac.chosun.mspace.report.application.ReportEvidencePort.Times;
import org.springframework.stereotype.Service;

/** 신고 판정용 증거 사진. 직전 사람을 고르는 규칙은 경고 대상(ReportPenaltyPort.findViolator)과 같다 */
@Service
class ReportEvidenceService implements ReportEvidenceUseCase {

    static final String CHECK_IN = "CHECK_IN";
    static final String CHECK_OUT = "CHECK_OUT";

    private final ReportJudgePort reports;
    private final ReportPenaltyPort penalties;
    private final ReportEvidencePort photos;

    ReportEvidenceService(ReportJudgePort reports, ReportPenaltyPort penalties, ReportEvidencePort photos) {
        this.reports = reports;
        this.penalties = penalties;
        this.photos = photos;
    }

    @Override
    public Evidence evidence(long adminId, long reportId) {
        if (!reports.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        ReportItem report = reports.find(reportId).orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        List<String> notes = new ArrayList<>();

        Previous previous = penalties.findViolator(reportId).map(v -> {
            Times t = photos.times(v.reservationId()).orElse(new Times(null, null));
            return new Previous(v.reservationId(), t.checkedInAt(), t.checkedOutAt(), photo(v.reservationId(), CHECK_OUT),
                    photos.tidyCheck(v.reservationId()).orElse(null));
        }).orElse(null);
        if (previous == null) {
            notes.add("신고 전에 이 방을 쓴 예약이 없어요.");
        } else if (!previous.photo().exists()) {
            notes.add("직전 이용자가 퇴실 사진을 올리지 않았어요 (또는 30일이 지나 지워졌어요).");
        }

        Long reporterReservation = report.reporterReservationId();
        Side reporter = null;
        if (reporterReservation == null) {
            notes.add("방 카메라 자동 신고라 신고자 사진이 없어요.");
        } else {
            reporter = new Side(reporterReservation, photo(reporterReservation, CHECK_IN));
            if (!reporter.photo().exists()) {
                notes.add("신고자가 입실 사진을 올리지 않았어요 (또는 30일이 지나 지워졌어요).");
            }
        }
        return new Evidence(reportId, previous, reporter, notes.isEmpty() ? null : String.join(" ", notes));
    }

    private Photo photo(long reservationId, String kind) {
        Instant at = photos.photoUploadedAt(reservationId, kind).orElse(null);
        return at == null ? new Photo(kind, false, null, null)
                : new Photo(kind, true, "/api/reservations/" + reservationId + "/photos/" + kind, at);
    }
}
