package kr.ac.chosun.mspace.notification.application;

import java.time.Instant;

/** 알림 읽음 표시 (FR-NTF-103 "안 읽은 알림은 점 표시") */
public interface NotificationReadPort {

    /**
     * 내 알림 한 건을 읽음으로. 이미 읽었으면 처음 읽은 시각을 그대로 둔다.
     * @return 내 알림이 있으면 true (없거나 남의 알림이면 false)
     */
    boolean markRead(long userId, long notificationId, Instant now);

    /** 내 안 읽은 알림 전부를 읽음으로. 바뀐 개수 */
    int markAllRead(long userId, Instant now);

    /** 내 안 읽은 알림 개수 (종 아이콘 점) */
    int countUnread(long userId);
}
