package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;

/** 신고 자동 판정 저장소 (V30). 구현: JdbcAutoJudgeAdapter */
public interface AutoJudgePort {

    /** 판정 대기인 학생 신고(소음 빼고) 중 createdBefore 전에 들어온 것, 오래된 것부터 */
    List<Long> openReports(Instant createdBefore, int limit);

    /** 아직 판정 대기면 판정기에 넘길 사실 */
    Optional<ReportJudge.Case> loadCase(long reportId);

    /** 판정 기록. applied = 확신도 기준을 적용한 뒤 실제로 정한 판정 */
    void record(long reportId, ReportJudge.Judgement judgement, ReportVerdict applied, Instant now);
}
