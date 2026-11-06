package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.report.domain.ReportCategory;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;

/** 관리자 신고 목록·판정 입력 포트 (FR-RPT-103). 구현: {@link JudgeReportService} */
public interface JudgeReportUseCase {

    /**
     * 신고 목록. 최근 신고가 위. 관리자만.
     *
     * @param verdict null이면 전부
     */
    List<ReportItem> list(long adminId, ReportVerdict verdict);

    /** 판정 대기(OPEN)인 신고를 한 번 판정한다. 관리자만 */
    ReportItem judge(long adminId, long reportId, ReportVerdict verdict);

    /** @param reporterReservationId 신고 권한 근거 예약 (그 시각 신고자가 어느 방에 있었는지) */
    record ReportItem(long id, int roomId, ReportCategory category, String content, ReportVerdict verdict,
                      String source, Long reporterReservationId, Instant createdAt, Instant judgedAt) {
    }
}
