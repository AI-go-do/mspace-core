package kr.ac.chosun.mspace.notification.application;

import java.time.Clock;
import java.util.List;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/** 앱 알림함 (FR-NTF-103): 목록, 읽음 표시, 안 읽은 개수 */
@Service
public class MyNotificationsService {

    private static final int LIMIT = 50;

    private final NotificationQueryPort notifications;
    private final NotificationReadPort reads;
    private final Clock clock;

    MyNotificationsService(NotificationQueryPort notifications, NotificationReadPort reads, Clock clock) {
        this.notifications = notifications;
        this.reads = reads;
        this.clock = clock;
    }

    public List<Notification> findMine(long userId) {
        return notifications.findRecent(userId, LIMIT);
    }

    /** 알림 하나를 눌렀을 때. 두 번 불러도 같은 결과 */
    public void markRead(long userId, long notificationId) {
        if (!reads.markRead(userId, notificationId, clock.instant())) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    /** "모두 읽음" */
    public int markAllRead(long userId) {
        return reads.markAllRead(userId, clock.instant());
    }

    public int countUnread(long userId) {
        return reads.countUnread(userId);
    }
}
