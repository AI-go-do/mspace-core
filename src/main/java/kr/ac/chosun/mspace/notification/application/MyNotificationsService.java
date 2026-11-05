package kr.ac.chosun.mspace.notification.application;

import java.util.List;
import org.springframework.stereotype.Service;

/** 앱 알림함 (FR-NTF-103) 목록. 읽음 처리·종 아이콘 점은 화면 작업 때 붙인다 */
@Service
public class MyNotificationsService {

    private static final int LIMIT = 50;

    private final NotificationQueryPort notifications;

    MyNotificationsService(NotificationQueryPort notifications) {
        this.notifications = notifications;
    }

    public List<Notification> findMine(long userId) {
        return notifications.findRecent(userId, LIMIT);
    }
}
