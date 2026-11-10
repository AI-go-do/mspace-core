package kr.ac.chosun.mspace.appeal.application;

import java.util.List;

/**
 * 경고 철회 요청을 다시 보는 판단기 (FR-RPT-105, Q-24 ④ 11/10 영준 확인: core 포트 + AI 어댑터).
 * 어댑터를 바꿔 끼워도(규칙 → Gemini → 젯슨) 판단 기록 모양은 같다. 결과 적용(철회·정지 풀기·알림)은 core가 한다.
 * 이름·학번은 넘기지 않는다.
 */
public interface WarningReviewer {

    enum Decision { WITHDRAWN, KEPT }

    /**
     * 판단에 쓸 사실.
     *
     * @param reason              VIOLATION(위반) · FALSE_REPORT(허위 신고)
     * @param reportSource        USER(학생 신고) · CAMERA(방 카메라 다시 확인)
     * @param reportType          MESSY · WINDOW_OPEN · BROKEN · NOISE
     * @param reportContent       신고 메모 (없으면 null)
     * @param appealText          학생이 쓴 사유 (긴 숫자는 이미 막음)
     * @param evidenceAsked       학생이 같이 봐 달라고 고른 기록
     * @param checkInPhoto        그 예약의 입실 사진이 있나
     * @param checkOutPhoto       그 예약의 퇴실 사진이 있나
     * @param recheckItems        카메라 경고면 다시 확인 때 남아 있던 물건 이름 (아니면 빈 목록)
     * @param recheckConfidences  recheckItems와 같은 순서의 확신도
     */
    record Case(long appealId, String reason, String reportSource, String reportType, String reportContent,
                String appealText, List<String> evidenceAsked, boolean checkInPhoto, boolean checkOutPhoto,
                List<String> recheckItems, List<Double> recheckConfidences) {
    }

    /**
     * 판단 결과와 기록.
     *
     * @param confidence    이 결정에 대한 확신도 0~1
     * @param reasons       이유 (학생에게 한 문단으로 보여 줌)
     * @param evidenceUsed  실제로 보고 정한 기록
     * @param reviewer      판단기 이름(모델). 정확도 표를 판단기별로 낸다
     * @param promptVersion 지시문(규칙) 버전
     */
    record Review(Decision decision, double confidence, List<String> reasons, List<String> evidenceUsed,
                  String reviewer, String promptVersion) {
    }

    Review review(Case c);
}
