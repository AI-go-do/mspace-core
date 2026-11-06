package kr.ac.chosun.mspace.report.application;

import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.report.application.JudgeReportUseCase.ReportItem;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;

/** 신고 판정 저장소 출력 포트. 구현: JdbcReportAdapter */
public interface ReportJudgePort {

    /** 관리자(users.role = ADMIN)인지. 없는 회원이면 false */
    boolean isAdmin(long userId);

    List<ReportItem> list(ReportVerdict verdict);

    Optional<ReportItem> find(long reportId);

    /**
     * 판정 대기(OPEN)이고 소음이 아닐 때만 판정을 바꾼다 (조건부 UPDATE 한 번).
     *
     * @return 바뀐 신고. 조건이 안 맞으면 빈 값
     */
    Optional<ReportItem> judgeIfOpen(long reportId, long adminId, ReportVerdict verdict);
}
