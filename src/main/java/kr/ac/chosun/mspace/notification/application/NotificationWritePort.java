package kr.ac.chosun.mspace.notification.application;

import java.time.Instant;
import java.util.List;

public interface NotificationWritePort {

    /** 알림함에 넣는다 (한 트랜잭션). 넣은 순서대로 돌려준다 */
    List<Notification> saveAll(List<NotificationInbox.NewNotification> items, Instant now);
}
