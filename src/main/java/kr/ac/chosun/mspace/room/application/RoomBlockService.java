package kr.ac.chosun.mspace.room.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관리자 기간 막기 (Q-23, core #95).
 * <ul>
 *   <li>시각은 예약과 같은 30분 칸, 끝 &gt; 시작, 끝이 지금보다 뒤, 최대 {@value RoomBlockUseCase#MAX_DAYS}일, 메모
 *       {@value RoomBlockUseCase#MEMO_MAX}자 → 아니면 400 ROOM_BLOCK_INVALID</li>
 *   <li>저장은 예약 저장과 같은 방 잠금 안에서 한다. 그래서 막기와 학생 예약이 동시에 와도, 먼저 저장된 예약은 "겹친 예약"
 *       목록에 나오고 늦은 예약은 409 ROOM_BLOCKED를 받는다 (둘 다 빠져나가는 틈이 없다)</li>
 *   <li>겹친 예약 취소와 알림함 저장은 한 트랜잭션. 메일 등 바깥 채널은 커밋 뒤</li>
 * </ul>
 */
@Service
class RoomBlockService implements RoomBlockUseCase {

    static final String KIND = "ROOM_BLOCKED";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M/d HH:mm");

    private final RoomBlockPort port;
    private final RoomQuery rooms;
    private final NotificationInbox inbox;
    private final ReservationPolicy policy;
    private final TransactionOperations tx;
    private final Clock clock;

    RoomBlockService(RoomBlockPort port, RoomQuery rooms, NotificationInbox inbox, ReservationPolicy policy,
                     TransactionOperations tx, Clock clock) {
        this.port = port;
        this.rooms = rooms;
        this.inbox = inbox;
        this.policy = policy;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public List<Block> list(long adminId) {
        checkAdmin(adminId);
        return port.findEndingAfter(clock.instant().minus(Duration.ofDays(LIST_PAST_DAYS)));
    }

    @Override
    public Created create(long adminId, NewBlock block) {
        checkAdmin(adminId);
        Instant now = clock.instant();
        NewBlock b = validate(block, now);
        rooms.findActive(b.roomId()).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        return tx.execute(s -> {
            port.lockRoom(b.roomId());
            Block saved = port.insert(adminId, b, now);
            return new Created(saved, port.conflicts(b.roomId(), b.startAt(), b.endAt(), now));
        });
    }

    @Override
    public Block release(long adminId, long blockId) {
        checkAdmin(adminId);
        return tx.execute(s -> {
            // 겹친 예약 취소(cancelConflicts)와 같은 방 잠금으로 줄 세움: 푸는 중에 취소가 끼어들지 않게
            port.lockRoom(find(blockId).roomId());
            Block b = find(blockId);
            if (b.releasedAt() != null) {
                return b;
            }
            port.release(blockId, adminId, clock.instant());
            return find(blockId);
        });
    }

    @Override
    public List<Conflict> conflicts(long adminId, long blockId) {
        checkAdmin(adminId);
        Instant now = clock.instant();
        Block b = find(blockId);
        if (!active(b, now)) {
            return List.of();
        }
        return port.conflicts(b.roomId(), b.startAt().toInstant(), b.endAt().toInstant(), now);
    }

    @Override
    public Canceled cancelConflicts(long adminId, long blockId) {
        checkAdmin(adminId);
        List<Notification> stored = new ArrayList<>();
        List<Long> canceled = tx.execute(s -> {
            // 잠금을 먼저 잡고 막기 상태와 지금 시각을 다시 읽음: 기다리는 사이 풀렸거나 예약이 시작됐을 수 있음
            port.lockRoom(find(blockId).roomId());
            Block b = find(blockId);
            Instant now = clock.instant();
            if (!active(b, now)) {
                return List.<Long>of();
            }
            List<Long> ids = port.cancelBooked(b.roomId(), b.startAt().toInstant(), b.endAt().toInstant(), now);
            String body = "관리자가 %s–%s %s을 막아서 예약이 취소됐어요 (사유: %s%s)".formatted(
                    WHEN.format(b.startAt()), WHEN.format(b.endAt()), port.roomName(b.roomId()), b.reason().label(),
                    b.memo() == null ? "" : ", " + b.memo());
            List<NewNotification> items = new ArrayList<>();
            for (long id : ids) {
                port.members(id).forEach(u -> items.add(
                        new NewNotification(u, id, KIND, "관리자가 방을 막아 예약이 취소됐어요", body)));
            }
            stored.addAll(inbox.store(items, now));
            return ids;
        });
        inbox.publish(stored);
        return new Canceled(canceled.size(), canceled);
    }

    private Block find(long blockId) {
        return port.find(blockId).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_BLOCK_NOT_FOUND));
    }

    private static boolean active(Block b, Instant now) {
        return b.releasedAt() == null && b.endAt().toInstant().isAfter(now);
    }

    private void checkAdmin(long userId) {
        if (!port.isAdmin(userId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
    }

    /** 넣은 값 확인. 메모는 앞뒤 공백을 떼고 빈 글이면 null */
    private NewBlock validate(NewBlock b, Instant now) {
        if (b.startAt() == null || b.endAt() == null || b.reason() == null) {
            throw invalid("방·시작·끝·사유를 모두 넣어 주세요");
        }
        long slotSeconds = policy.slotMinutes() * 60L;
        if (b.startAt().getEpochSecond() % slotSeconds != 0 || b.endAt().getEpochSecond() % slotSeconds != 0
                || b.startAt().getNano() != 0 || b.endAt().getNano() != 0) {
            throw invalid("시각은 %d분 단위(정각·30분)로 넣어 주세요".formatted(policy.slotMinutes()));
        }
        if (!b.endAt().isAfter(b.startAt())) {
            throw invalid("끝이 시작보다 뒤여야 해요");
        }
        if (!b.endAt().isAfter(now)) {
            throw invalid("이미 지난 기간은 막을 수 없어요");
        }
        if (Duration.between(b.startAt(), b.endAt()).compareTo(Duration.ofDays(MAX_DAYS)) > 0) {
            throw invalid("한 번에 %d일까지 막을 수 있어요. 더 길면 나눠서 넣어 주세요".formatted(MAX_DAYS));
        }
        String memo = b.memo() == null || b.memo().isBlank() ? null : b.memo().strip();
        if (memo != null && memo.length() > MEMO_MAX) {
            throw invalid("메모는 %d자까지예요".formatted(MEMO_MAX));
        }
        return new NewBlock(b.roomId(), b.startAt(), b.endAt(), b.reason(), memo);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.ROOM_BLOCK_INVALID, message);
    }
}
