package kr.ac.chosun.mspace.report.application;

import org.springframework.stereotype.Service;

@Service
class CameraReportService implements CameraReportUseCase {

    private final ReportPort reports;

    CameraReportService(ReportPort reports) {
        this.reports = reports;
    }

    @Override
    public long fileMessy(int roomId, long targetReservationId, String content) {
        return reports.saveCamera(roomId, targetReservationId, content);
    }
}
