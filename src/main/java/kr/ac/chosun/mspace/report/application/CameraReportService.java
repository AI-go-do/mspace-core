package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
class CameraReportService implements CameraReportUseCase {

    private final ReportPort reports;
    private final JudgeReportService judge;

    CameraReportService(ReportPort reports, JudgeReportService judge) {
        this.reports = reports;
        this.judge = judge;
    }

    @Override
    public Warned warnLeftItems(int roomId, long reservationId, String content, String why, Instant now) {
        long reportId = reports.saveCamera(roomId, reservationId, content);
        return new Warned(reportId, judge.confirmBySystem(reportId, why, now));
    }
}
