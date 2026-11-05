package kr.ac.chosun.mspace.notification.application;

import java.time.Instant;

/**
 * 한 사람에게 가는 알림 한 건. 앱 알림함(notification 테이블)에 저장된 뒤의 모습.
 * 문구에는 이름·학번을 넣지 않는다 (방 이름·시각만). 메일 어댑터는 userId로 받는 사람 주소를 찾는다.
 */
public record Notification(long id, long userId, Long reservationId, String kind, String title, String body,
                           Instant createdAt, Instant readAt) {
}
