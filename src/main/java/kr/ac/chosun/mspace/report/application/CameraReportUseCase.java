package kr.ac.chosun.mspace.report.application;

/**
 * 방 카메라가 "치울 것 있음"을 보면 어질러짐 신고를 자동으로 만든다 (FR-DEV-105, 11/6 지영 "자동신고+관리자 판정").
 * 신고자가 없고 경고 대상 예약을 바로 안다. 판정·경고는 사람 신고와 같은 흐름(JudgeReportService)을 탄다.
 * 부르는 쪽 트랜잭션에 같이 들어간다.
 */
public interface CameraReportUseCase {

    /** @return 새 신고 id */
    long fileMessy(int roomId, long targetReservationId, String content);
}
