package kr.ac.chosun.mspace.notification.adapter.in.web;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.notification.application.MyNotificationsService;
import kr.ac.chosun.mspace.notification.application.Notification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    /** 안 읽은 알림 개수 (종 아이콘 점). 예: {"count":3} */
    @GetMapping("/api/notifications/me/unread-count")
    UnreadCount unreadCount(LoginUser user) {
        return new UnreadCount(notifications.countUnread(user.id()));
    }

    /** 알림 하나 읽음. 이미 읽었어도 204. 없거나 남의 알림이면 404 NOTIFICATION_NOT_FOUND */
    @PostMapping("/api/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(LoginUser user, @PathVariable long id) {
        notifications.markRead(user.id(), id);
    }

    /** 내 알림 모두 읽음. 예: {"count":3} (이번에 읽음으로 바뀐 개수) */
    @PostMapping("/api/notifications/read-all")
    UnreadCount readAll(LoginUser user) {
        return new UnreadCount(notifications.markAllRead(user.id()));
    }

    record UnreadCount(int count) {
    }

    record NotificationResponse(long id, Long reservationId, String kind, String title, String body,
                                OffsetDateTime createdAt, boolean read) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.id(), n.reservationId(), n.kind(), n.title(), n.body(),
                    n.createdAt().atZone(SEOUL).toOffsetDateTime(), n.readAt() != null);
        }
    }
}
