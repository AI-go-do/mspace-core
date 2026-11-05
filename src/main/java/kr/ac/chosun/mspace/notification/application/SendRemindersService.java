package kr.ac.chosun.mspace.notification.application;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 1) 보낼 알림을 기록·알림함 저장(한 트랜잭션, ReminderPort) → 2) 커밋된 뒤 바깥 채널(메일 등)로 내보낸다.
 * 바깥 채널을 트랜잭션 밖에서 부르는 이유: 메일 서버가 느려도 DB 잠금을 오래 쥐지 않게, 메일이 실패해도 알림함은 남게.
 * 로그에는 건수·종류·예약 번호만 남긴다 (이름·메일 주소·문구는 남기지 않음).
 */
@Service
class SendRemindersService implements SendRemindersUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendRemindersService.class);

    private final ReminderPort reminders;
    private final ObjectProvider<NotificationChannel> channels;
    private final Clock clock;

    SendRemindersService(ReminderPort reminders, ObjectProvider<NotificationChannel> channels, Clock clock) {
        this.reminders = reminders;
        this.channels = channels;
        this.clock = clock;
    }

    @Override
    public int sendDue() {
        List<Notification> created;
        try {
            created = reminders.claimDue(clock.instant());
        } catch (PessimisticLockingFailureException e) {
            log.warn("예약 알림: 잠금 충돌({})로 이번에는 건너뜀, 다음 번에 다시", e.getClass().getSimpleName());
            return 0;
        }
        if (created.isEmpty()) {
            return 0;
        }
        log.info("예약 알림: {}건", created.size());
        channels.orderedStream().forEach(channel -> created.forEach(n -> {
            try {
                channel.send(n);
            } catch (RuntimeException e) {
                // 한 사람 실패가 나머지를 막지 않게. 알림함에는 이미 있다
                log.warn("예약 알림 내보내기 실패: 채널 {}, 종류 {}, 예약 {} ({})", channel.getClass().getSimpleName(),
                        n.kind(), n.reservationId(), e.getClass().getSimpleName());
            }
        }));
        return created.size();
    }
}
