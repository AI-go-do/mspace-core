package kr.ac.chosun.mspace.report.application;

import java.time.Instant;

/** 내 경고 횟수·정지 (FR-WEB-003 "내 경고 0회", FR-RPT-104). 구현: {@link MyPenaltyService} */
public interface MyPenaltyUseCase {

    /** 로그인한 본인의 것만 */
    MyPenalty mine(long userId);

    /**
     * @param warnings             위반 경고 수 (windowDays 안, 지난 이용 정지 뒤의 것만. 정지 판단과 같은 셈)
     * @param falseReportWarnings  허위 신고 경고 수 (같은 방식, 신고 정지 기준)
     * @param limit                이 횟수가 되면 정지
     * @param suspendedUntil       이용 정지 중이면 끝나는 시각, 아니면 null
     * @param reportSuspendedUntil 신고 정지 중이면 끝나는 시각, 아니면 null
     */
    record MyPenalty(int warnings, int falseReportWarnings, int limit, int windowDays, Instant suspendedUntil,
                     Instant reportSuspendedUntil) {
    }
}
