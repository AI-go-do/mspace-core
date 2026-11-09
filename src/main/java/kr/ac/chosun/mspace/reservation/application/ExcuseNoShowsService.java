package kr.ac.chosun.mspace.reservation.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.reservation.application.ExcuseNoShowsPort.Excused;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 방의 사과 (Q-19). 무르기·정지 풀기·알림함 저장은 한 트랜잭션, 메일 등 바깥 채널은 커밋 뒤.
 * <ul>
 *   <li>상태는 NO_SHOW 그대로 두고(기록·통계) 벌칙 세기에서만 뺀다. 상태 트리거가 NO_SHOW에서 다른 상태로 못 가게 막기도 한다</li>
 *   <li>이 노쇼 뒤에 걸린 노쇼 정지는 푼다. 다른 노쇼와 합쳐 걸린 정지여도 푼다 (장치 탓이 섞인 정지는 학생 편으로)</li>
 *   <li>대표자·참여자 모두에게 사과 알림</li>
 * </ul>
 */
@Service
class ExcuseNoShowsService implements ExcuseNoShowsUseCase {

    static final String KIND = "DEVICE_APOLOGY";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M/d HH:mm");

    private final ExcuseNoShowsPort port;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;

    ExcuseNoShowsService(ExcuseNoShowsPort port, NotificationInbox inbox, TransactionOperations tx) {
        this.port = port;
        this.inbox = inbox;
        this.tx = tx;
    }

    @Override
    public int excuse(Instant now) {
        List<Notification> stored = new ArrayList<>();
        int count = tx.execute(s -> {
            List<Excused> excused = port.excuseMatched(now);
            List<NewNotification> items = new ArrayList<>();
            for (Excused e : excused) {
                boolean lifted = port.liftSuspension(e.ownerId(), e.noShowAt(), now);
                String body = "%s %s 예약은 문 장치 문제로 입실을 못 하셔서 노쇼에서 뺐어요.%s 불편을 드려 죄송해요."
                        .formatted(e.roomName(), WHEN.format(e.startAt().atZone(SEOUL)),
                                lifted ? " 그 때문에 걸린 이용 정지도 풀었어요." : "");
                port.members(e.reservationId()).forEach(u -> items.add(
                        new NewNotification(u, e.reservationId(), KIND, "장치 문제로 못 들어가셨어요", body)));
            }
            stored.addAll(inbox.store(items, now));
            return excused.size();
        });
        inbox.publish(stored);
        return count;
    }
}
