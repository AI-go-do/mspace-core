package kr.ac.chosun.mspace.notification.application;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 1) 보낼 알림을 기록·알림함 저장(한 트랜잭션, ReminderPort) → 2) 커밋된 뒤 바깥 채널(메일 등)로 내보낸다(NotificationPublisher).
 */
@Service
class SendRemindersService implements SendRemindersUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendRemindersService.class);

    private final ReminderPort reminders;
    private final NotificationPublisher publisher;
    private final Clock clock;

    SendRemindersService(ReminderPort reminders, NotificationPublisher publisher, Clock clock) {
        this.reminders = reminders;
        this.publisher = publisher;
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
        publisher.publish(created);
        return created.size();
    }
}
