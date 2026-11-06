package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import kr.ac.chosun.mspace.report.domain.ReportCategory;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;

/** 신고 입력 포트 (FR-RPT-101). 구현: {@link SubmitReportService} */
public interface SubmitReportUseCase {

    /**
     * 지금 사용 중인 예약의 이용자(대표자·참여자)가 그 방이나 옆 방을 신고한다.
     *
     * @param userId 로그인 회원 id
     * @return 저장된 신고. 판정은 OPEN(대기)
     */
    Submitted submit(long userId, NewReport report);

    /** @param noiseConfirmed "정말 소란인가요?" 체크. 소음(NOISE)일 때만 필수 */
    record NewReport(int roomId, ReportCategory category, String content, boolean noiseConfirmed) {
    }

    record Submitted(long id, int roomId, ReportCategory category, ReportVerdict verdict, Instant createdAt) {
    }
}
