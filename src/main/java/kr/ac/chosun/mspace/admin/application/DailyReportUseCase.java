package kr.ac.chosun.mspace.admin.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase.Usage;

/** 관리자 하루 운영 보고: 하루 숫자 + AI가 쓴 3–5줄 요약 (11/12 지영 "core 다 AI화", 관제 화면). 구현: {@link DailyReportService} */
public interface DailyReportUseCase {

    /** @param date 서울 날짜. 빼면 오늘. 미래면 400, 관리자가 아니면 403 */
    DailyReport report(long adminId, LocalDate date);

    /**
     * @param lines       요약 3–5줄 (관리자 화면에 그대로)
     * @param writer      누가 썼나: gemini:모델 또는 template(키가 없거나 AI가 실패하면 정해진 틀로)
     * @param generatedAt 요약을 만든 시각. 오늘 보고는 refresh(1시간)마다 새로 만든다
     * @param facts       요약에 쓴 숫자 (화면이 숫자를 따로 보여 줄 때)
     */
    record DailyReport(LocalDate date, List<String> lines, String writer, Instant generatedAt, Facts facts) {
    }

    /**
     * 하루 숫자. 이름·학번 없음. 신고·경고·철회·고장은 그날(서울) 생긴 것만 센다.
     *
     * @param partial      오늘이라 하루가 아직 안 끝났나
     * @param total        이용 통계 전체 (GET /api/stats/usage 하루치와 같은 값)
     * @param rooms        방별 이용 통계
     * @param reports      신고 종류 → 판정(OPEN·CONFIRMED·UNVERIFIED·FALSE) → 건수
     * @param autoJudged   그중 판정기가 정한 건수
     * @param warnings     경고 이유(VIOLATION·FALSE_REPORT) → 건수
     * @param appeals      그날 정해진 경고 철회 요청 결과(WITHDRAWN·KEPT) → 건수, 대기(PENDING)는 그날 들어온 것
     * @param deviceFaults 장치 고장 종류 → 건수
     */
    record Facts(boolean partial, Usage total, List<Usage> rooms, Map<String, Map<String, Long>> reports,
                 long autoJudged, Map<String, Long> warnings, Map<String, Long> appeals, Map<String, Long> deviceFaults) {
    }
}
