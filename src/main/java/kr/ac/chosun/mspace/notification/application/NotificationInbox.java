package kr.ac.chosun.mspace.notification.application;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 다른 기능(센서 환기 알림 등)이 앱 알림함에 알림을 넣는 입구 (FR-NTF-103).
 * 1) store: 부르는 쪽 트랜잭션 안에서 알림함에 저장 → 2) publish: 커밋된 뒤 바깥 채널(메일 등)로 내보낸다.
 * 예약 알림 3종처럼 "예약·사람·종류별 한 번만"이 필요한 알림은 notification_log를 쓰는 ReminderPort가 따로 맡는다.
 * 여기 오는 알림의 중복은 부르는 쪽이 막는다.
 */
@Service
public class NotificationInbox {

    private final NotificationWritePort notifications;
    private final NotificationPublisher publisher;

    NotificationInbox(NotificationWritePort notifications, NotificationPublisher publisher) {
        this.notifications = notifications;
        this.publisher = publisher;
    }

    /** 한 사람에게 갈 새 알림. 문구에는 이름·학번을 넣지 않는다 */
    public record NewNotification(long userId, Long reservationId, String kind, String title, String body) {
    }

    /** 알림함에 저장한다. 부르는 쪽 트랜잭션에 같이 들어간다 */
    public List<Notification> store(List<NewNotification> items, Instant now) {
        return items.isEmpty() ? List.of() : notifications.saveAll(items, now);
    }

    /** 커밋된 알림을 바깥 채널로. 트랜잭션 밖에서 부른다 */
    public void publish(List<Notification> stored) {
        publisher.publish(stored);
    }
}
