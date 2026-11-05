package kr.ac.chosun.mspace.notification.application;

/**
 * 알림을 바깥으로 내보내는 길 (메일·텔레그램·푸시). 메일 구현은 재웅 담당 (요구사항 NFR-COM-006 표, 이슈 #14).
 * 빈(bean)으로 등록만 하면 SendRemindersService가 새 알림마다 부른다. 하나도 없으면 앱 알림함에만 남는다.
 * <p>
 * 부르는 때는 알림함 저장이 커밋된 뒤라, 여기서 실패해도 알림함과 "한 번만" 기록(notification_log)은 그대로다.
 * 그래서 같은 알림을 다시 보내지는 않는다 (최대 한 번). 오래 걸리면 1분 정리가 밀리니 시간 제한을 둔다.
 */
public interface NotificationChannel {

    void send(Notification notification);
}
