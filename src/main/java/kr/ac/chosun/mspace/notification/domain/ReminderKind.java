package kr.ac.chosun.mspace.notification.domain;

/** 예약 알림 종류. notification_log.kind CHECK(V10)와 같은 이름 */
public enum ReminderKind {
    /** FR-NTF-101 입실 N분 전 */
    START_SOON,
    /** FR-NTF-102 종료 N분 전 */
    END_SOON,
    /** FR-NTF-104 노쇼 N분 전 */
    NO_SHOW_SOON,
    /** FR-DEV-103 자리 비움 N분: 돌아오지 않으면 곧 자동 퇴실 */
    AWAY_SOON
}
