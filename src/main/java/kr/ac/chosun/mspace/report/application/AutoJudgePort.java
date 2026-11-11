package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;

/** 신고 자동 판정 저장소 (V30). 구현: JdbcAutoJudgeAdapter */
public interface AutoJudgePort {

    /**
     * 판정 대기인 학생 신고(소음 빼고) 중 createdBefore 전에 들어온 것, 오래된 것부터.
     * 어질러짐은 앞 팀이 previousOutBefore 전에 퇴실했을 때만 (찾으러 올 3시간이 지나야, 11/11 지영)
     */
    List<Long> openReports(Instant createdBefore, Instant previousOutBefore, int limit);

    /** 아직 판정 대기면 판정기에 넘길 사실 */
    Optional<ReportJudge.Case> loadCase(long reportId);

    /**
     * 앞 팀 예약 줄을 잠그고(정리 다시 확인·찾으러 갈게요 QR과 같은 잠금) 남은 물건 일이 이미 끝났나 다시 본다:
     * 같은 예약에 어질러짐 경고가 있거나 QR로 문을 열었다. 판정기가 읽은 뒤 다른 길이 먼저 경고했을 때 두 번 경고하지 않게
     */
    boolean lockPreviousSettled(long previousReservationId);

    /** 판정 기록. applied = 확신도 기준을 적용한 뒤 실제로 정한 판정 */
    void record(long reportId, ReportJudge.Judgement judgement, ReportVerdict applied, Instant now);
}
