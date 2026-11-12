package kr.ac.chosun.mspace.report.application;

import java.util.List;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;

/**
 * 학생 신고를 판정하는 판정기 (FR-RPT-103 10차, Q-28 영준 11/10: 철회 판단기처럼 core 포트 + 어댑터).
 * 어댑터는 판정만 내고, 판정 적용(경고·정지·알림)과 기록은 core가 한다. 이름·학번은 넘기지 않는다.
 */
public interface ReportJudge {

    /**
     * 판정에 쓸 사실. 앞 팀 = 신고 직전에 그 방에 입실한 예약 (경고 받을 예약, FR-RPT-104).
     *
     * @param category              BROKEN · WINDOW_OPEN · MESSY (소음은 판정 대상 아님)
     * @param content               신고 메모 (없으면 null)
     * @param reporterCheckInPhoto  신고자 예약에 입실 사진이 있나
     * @param previousCheckOutPhoto 앞 팀 예약에 퇴실 사진이 있나
     * @param previousTidyResult    앞 팀 방 카메라 확인 결과 CLEAN · LEFT_ITEMS, 없으면 null.
     *                              3시간 뒤 다시 확인(RECHECK)이 있으면 그것, 없으면 퇴실 직후 첫 확인(FIRST)
     * @param previousItems         그때 본 물건 이름
     * @param previousConfidences   previousItems와 같은 순서의 확신도
     * @param previousWarned        앞 팀이 같은 퇴실로 이미 카메라 경고를 받았나 (같은 물건으로 두 번 경고하지 않게)
     * @param previousRetrieved     앞 팀이 "찾으러 갈게요" QR로 문을 열었나 (11/11 수연·지영: 열었으면 경고 안 함)
     * @param previousReservationId 앞 팀 예약 번호, 없으면 null (판정기에는 쓰지 않고 적용 때 다시 확인용)
     * @param previousRechecked     previousTidyResult가 다시 확인(RECHECK) 결과인가 (적용 때 다시 확인용)
     * @param roomAir               창문 열림 신고일 때만: 앞 팀 퇴실 앞뒤 방 CO2·온도. 다른 종류이거나 앞 팀이 없으면 null
     */
    record Case(long reportId, String category, String content, boolean reporterCheckInPhoto,
                boolean previousCheckOutPhoto, String previousTidyResult, List<String> previousItems,
                List<Double> previousConfidences, boolean previousWarned, boolean previousRetrieved,
                Long previousReservationId, boolean previousRechecked, RoomAir roomAir) {
    }

    /**
     * 방 센서(SCD40) 값을 5분씩 묶은 평균. 시각은 앞 팀 퇴실을 0분으로 센다 (퇴실 15분 전부터 신고 때까지, 최대 3시간)
     *
     * @param reportMinute 신고한 때 (퇴실 뒤 몇 분)
     * @param points       5분 묶음, 시간 순
     */
    record RoomAir(int reportMinute, List<AirPoint> points) {
    }

    /**
     * @param minute       묶음 시작 (퇴실 뒤 몇 분, 퇴실 전이면 음수)
     * @param ppm          CO2 평균
     * @param temperatureC 온도 평균, 센서가 안 보냈으면 null
     */
    record AirPoint(int minute, int ppm, Double temperatureC) {
    }

    /**
     * @param verdict       CONFIRMED(사실) · UNVERIFIED(확인 불가) · FALSE(허위)
     * @param confidence    이 판정이 맞을 확률 0~1
     * @param reasons       이유 (기록·관리자 화면용)
     * @param evidenceUsed  실제로 보고 정한 근거
     * @param judge         판정기 이름(모델). 정확도 표를 판정기별로 낸다
     * @param promptVersion 지시문(규칙) 버전
     */
    record Judgement(ReportVerdict verdict, double confidence, List<String> reasons, List<String> evidenceUsed,
                     String judge, String promptVersion) {
    }

    Judgement judge(Case c);
}
