package kr.ac.chosun.mspace.notification.adapter.in.scheduler;

import kr.ac.chosun.mspace.notification.application.SendRemindersUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1분마다 예약 알림. mspace.notification.reminder.enabled=false면 만들지 않는다 (테스트는 시계를 옮기며 직접 부름).
 * fixedDelay라 앞 번이 끝난 뒤 쉬고 다시 돈다 (겹쳐 돌지 않음). 최대 interval만큼 늦게 갈 수 있다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mspace.notification.reminder.enabled", havingValue = "true")
class ReminderScheduler {

    private final SendRemindersUseCase reminders;

    ReminderScheduler(SendRemindersUseCase reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${mspace.notification.reminder.interval}",
            initialDelayString = "${mspace.notification.reminder.interval}")
    void send() {
        reminders.sendDue();
    }
}
