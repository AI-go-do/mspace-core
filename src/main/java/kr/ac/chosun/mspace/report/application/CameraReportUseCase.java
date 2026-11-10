package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;

/**
 * 방 카메라가 만드는 신고 (FR-DEV-105). 신고자가 없고 경고 대상 예약을 바로 안다.
 * 11/6에는 "치울 것 있음"이면 바로 신고 + 관리자 판정이었고, 11/10 10차 회의부터는
 * 두고 간 물건(쓰레기 포함)을 기한 안에 안 가져갔을 때만 만들고 core가 바로 사실로 정한다.
 * 부르는 쪽 트랜잭션에 같이 들어간다.
 */
public interface CameraReportUseCase {

    /** @param reportId 경고 근거로 만든 카메라 신고 (사실로 판정된 상태) */
    record Warned(long reportId, List<NewNotification> notifications) {
    }

    /**
     * 카메라 신고를 만들고 사람 없이 사실로 판정해, 그 예약 대표자에게 경고(30일 3번이면 정지)를 남긴다.
     * 알림은 알림함에 넣을 것만 돌려준다.
     *
     * @param content 신고 내용 (500자 안)
     * @param why     경고 알림 첫 문장
     */
    Warned warnLeftItems(int roomId, long reservationId, String content, String why, Instant now);
}
