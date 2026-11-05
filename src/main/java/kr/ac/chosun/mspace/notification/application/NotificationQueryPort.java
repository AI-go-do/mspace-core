package kr.ac.chosun.mspace.notification.application;

import java.util.List;

public interface NotificationQueryPort {

    /** 내 알림 최근 순으로 limit개 */
    List<Notification> findRecent(long userId, int limit);
}
