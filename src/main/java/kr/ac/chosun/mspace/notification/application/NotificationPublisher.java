package kr.ac.chosun.mspace.notification.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 알림함에 저장(커밋)된 알림을 바깥 채널(메일 등, {@link NotificationChannel})로 내보낸다.
 * 트랜잭션 밖에서 부른다: 메일 서버가 느려도 DB 잠금을 쥐지 않게, 실패해도 알림함은 남게 (최대 한 번).
 * 로그에는 채널·종류·예약 번호만 (이름·주소·문구는 남기지 않음).
 */
@Service
public class NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    private final ObjectProvider<NotificationChannel> channels;

    NotificationPublisher(ObjectProvider<NotificationChannel> channels) {
        this.channels = channels;
    }

    public void publish(List<Notification> created) {
        if (created.isEmpty()) {
            return;
        }
        channels.orderedStream().forEach(channel -> created.forEach(n -> {
            try {
                channel.send(n);
            } catch (RuntimeException e) {
                // 한 사람 실패가 나머지를 막지 않게. 알림함에는 이미 있다
                log.warn("알림 내보내기 실패: 채널 {}, 종류 {}, 예약 {} ({})", channel.getClass().getSimpleName(),
                        n.kind(), n.reservationId(), e.getClass().getSimpleName());
            }
        }));
    }
}
