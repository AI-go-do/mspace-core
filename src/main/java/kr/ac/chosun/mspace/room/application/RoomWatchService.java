package kr.ac.chosun.mspace.room.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.room.application.RoomWatchPort.WatchRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 빈방 알림. 알림은 알림함에 같은 트랜잭션으로 넣고(알림 처리 표시와 함께), 바깥 채널은 커밋 뒤.
 * 비는 이유(퇴실·자동 퇴실·자리 비움·노쇼·취소)를 하나하나 잡지 않고 1분 정리가 "지금 빈지"를 본다. 늦어도 1분
 */
@Service
class RoomWatchService implements RoomWatchUseCase {

    static final String KIND = "ROOM_FREE";

    private final RoomWatchPort port;
    private final RoomQuery rooms;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;
    private final Clock clock;

    RoomWatchService(RoomWatchPort port, RoomQuery rooms, NotificationInbox inbox, TransactionOperations tx,
                     Clock clock) {
        this.port = port;
        this.rooms = rooms;
        this.inbox = inbox;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public List<Watch> myWatches(long userId) {
        Instant now = clock.instant();
        return port.myWatches(userId, LocalDate.ofInstant(now, SEOUL)).stream().map(RoomWatchService::watch).toList();
    }

    @Override
    public Watch watch(long userId, int roomId) {
        Instant now = clock.instant();
        rooms.findActive(roomId).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        if (!port.occupiedAt(roomId, now)) {
            throw new BusinessException(ErrorCode.ROOM_WATCH_FREE);
        }
        return watch(port.watch(userId, roomId, LocalDate.ofInstant(now, SEOUL), now));
    }

    @Override
    public void unwatch(long userId, int roomId) {
        Instant now = clock.instant();
        port.unwatch(userId, roomId, LocalDate.ofInstant(now, SEOUL), now);
    }

    @Override
    public int notifyFreed(Instant now) {
        List<Notification> stored = new ArrayList<>();
        Integer n = tx.execute(s -> {
            var freed = port.markFreed(LocalDate.ofInstant(now, SEOUL), now);
            stored.addAll(inbox.store(freed.stream().map(f -> new NewNotification(f.userId(), null, KIND,
                    "%s이 비었어요".formatted(f.roomName()),
                    "지켜보던 %s이 지금 비었어요. 바로 쓰려면 [지금 바로 쓰기]를 눌러 주세요.".formatted(f.roomName())))
                    .toList(), now));
            return freed.size();
        });
        inbox.publish(stored);
        return n == null ? 0 : n;
    }

    private static Watch watch(WatchRow w) {
        return new Watch(w.roomId(), w.createdAt().atZone(SEOUL));
    }
}
