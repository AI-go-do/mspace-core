package kr.ac.chosun.mspace.notification.adapter.in.web;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.notification.application.MyNotificationsService;
import kr.ac.chosun.mspace.notification.application.Notification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationController {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final MyNotificationsService notifications;

    NotificationController(MyNotificationsService notifications) {
        this.notifications = notifications;
    }

    /**
     * 내 알림함 (FR-NTF-103), 최근 50개.
     * 예: [{"id":3,"reservationId":12,"kind":"END_SOON","title":"종료 10분 전입니다",
     * "body":"창작실 1 11:30에 끝나요. ...","createdAt":"2030-03-05T11:20:00+09:00","read":false}]
     */
    @GetMapping("/api/notifications/me")
    List<NotificationResponse> mine(LoginUser user) {
        return notifications.findMine(user.id()).stream().map(NotificationResponse::from).toList();
    }

    record NotificationResponse(long id, Long reservationId, String kind, String title, String body,
                                OffsetDateTime createdAt, boolean read) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.id(), n.reservationId(), n.kind(), n.title(), n.body(),
                    n.createdAt().atZone(SEOUL).toOffsetDateTime(), n.readAt() != null);
        }
    }
}
