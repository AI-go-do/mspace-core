package kr.ac.chosun.mspace.reservation.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kr.ac.chosun.mspace.common.config.RelayPolicy;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.reservation.application.RelayPort.OfferRow;
import kr.ac.chosun.mspace.reservation.application.RelayPort.Released;
import kr.ac.chosun.mspace.reservation.application.RelayPort.SlotState;
import kr.ac.chosun.mspace.reservation.application.RelayPort.Span;
import kr.ac.chosun.mspace.reservation.application.RelayPort.WaitRow;
import kr.ac.chosun.mspace.room.application.RoomQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 빈방 릴레이 (Q-18). 규칙은 {@link RelayUseCase}.
 * <ul>
 *   <li>넘김 만들기는 예약 저장과 같은 방 잠금 안에서 한다. 넘겨받은 사람이 아닌 예약은 저장 때 409 RELAY_HELD
 *       (JdbcReservationAdapter). 같은 구간 열린 넘김은 DB 제약(one_open_offer)으로 하나뿐</li>
 *   <li>기다리기 걸기는 방 → 회원 잠금 안에서 칸을 보고 세고 넣는다 (동시에 4개를 걸어도 3개까지만,
 *       취소와 겹쳐도 빈 칸에 기다리기가 남지 않음)</li>
 *   <li>넘김 알림은 알림함에 같은 트랜잭션으로, 바깥 채널은 커밋 뒤</li>
 * </ul>
 */
@Service
class RelayService implements RelayUseCase {

    static final String KIND = "RELAY_OFFER";

    private static final Logger log = LoggerFactory.getLogger(RelayService.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M/d HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final RelayPort port;
    private final RoomQuery rooms;
    private final RelayPolicy relay;
    private final ReservationPolicy policy;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;
    private final Clock clock;

    RelayService(RelayPort port, RoomQuery rooms, RelayPolicy relay, ReservationPolicy policy, NotificationInbox inbox,
                 TransactionOperations tx, Clock clock) {
        this.port = port;
        this.rooms = rooms;
        this.relay = relay;
        this.policy = policy;
        this.inbox = inbox;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public List<Wait> myWaits(long userId) {
        return port.myWaits(userId, clock.instant()).stream().map(RelayService::wait).toList();
    }

    @Override
    public Wait addWait(long userId, int roomId, Instant startAt) {
        Instant now = clock.instant();
        Duration slot = Duration.ofMinutes(policy.slotMinutes());
        if (startAt == null || startAt.getNano() != 0 || startAt.getEpochSecond() % slot.toSeconds() != 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "기다릴 칸은 %d분 칸의 시작(정각·30분)으로 넣어 주세요".formatted(policy.slotMinutes()));
        }
        LocalDate last = LocalDate.ofInstant(now, SEOUL).plusDays(policy.bookingWindowDays());
        if (!startAt.isBefore(last.atStartOfDay(SEOUL).toInstant())) {
            throw new BusinessException(ErrorCode.RESERVATION_OUT_OF_RANGE);
        }
        if (!now.isBefore(startAt.minus(Duration.ofMinutes(relay.stopBeforeMinutes())))) {
            throw new BusinessException(ErrorCode.RELAY_TOO_LATE,
                    "시작 %d분 전부터는 기다리기를 걸 수 없어요".formatted(relay.stopBeforeMinutes()));
        }
        rooms.findActive(roomId).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        return tx.execute(s -> {
            // 예약 저장·넘김 만들기와 같은 순서(방 → 회원). 방 잠금 안에서 칸을 봐야 취소와 겹쳐도
            // released()가 이 기다리기 커밋을 기다렸다가 보고 넘긴다
            port.lockRoom(roomId);
            port.lockUser(userId);
            Instant locked = clock.instant();  // 잠금을 기다리는 사이 마감이 지났을 수 있음
            if (!open(startAt, locked)) {
                throw new BusinessException(ErrorCode.RELAY_TOO_LATE,
                        "시작 %d분 전부터는 기다리기를 걸 수 없어요".formatted(relay.stopBeforeMinutes()));
            }
            Optional<WaitRow> same = port.findActiveWait(userId, roomId, startAt);
            if (same.isPresent()) {
                return wait(same.get());
            }
            SlotState state = port.slotState(roomId, startAt, startAt.plus(slot), userId);
            if (!state.taken()) {
                throw new BusinessException(ErrorCode.RELAY_SLOT_FREE);
            }
            if (state.mine()) {
                throw new BusinessException(ErrorCode.RELAY_SLOT_FREE, "내가 들어 있는 예약이라 기다릴 필요가 없어요");
            }
            if (port.countActiveWaits(userId, locked) >= relay.maxWaits()) {
                throw new BusinessException(ErrorCode.RELAY_WAIT_LIMIT,
                        "기다리기는 한 사람당 동시에 %d개까지예요".formatted(relay.maxWaits()));
            }
            return wait(port.insertWait(userId, roomId, startAt, locked));
        });
    }

    @Override
    public void cancelWait(long userId, long waitId) {
        if (!port.cancelWait(userId, waitId, clock.instant())) {
            throw new BusinessException(ErrorCode.RELAY_WAIT_NOT_FOUND);
        }
    }

    @Override
    public List<Offer> myOffers(long userId) {
        return port.myOpenOffers(userId, clock.instant()).stream().map(RelayService::offer).toList();
    }

    @Override
    public void decline(long userId, long offerId) {
        Instant now = clock.instant();
        if (!port.declineOffer(userId, offerId, now)) {
            throw new BusinessException(ErrorCode.RELAY_OFFER_NOT_FOUND);
        }
        // 넘기기는 이미 저장됐으니, 다음 사람에게 넘기는 일이 실패해도 응답은 성공. 1분 정리가 다시 넘긴다
        try {
            advance(now);
        } catch (RuntimeException e) {
            log.warn("릴레이 다음 사람에게 넘기기 실패 (1분 정리가 다시 함): {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void released(long reservationId) {
        Instant now = clock.instant();
        Released r = port.canceledReservation(reservationId).orElse(null);
        if (r == null || !open(r.startAt(), now)) {
            return;  // 시작 30분 전부터는 넘기지 않고 그냥 빈 칸
        }
        List<Notification> stored = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            port.lockRoom(r.roomId());
            Instant locked = clock.instant();  // 잠금 뒤 시각으로 마감·5분을 잼
            if (open(r.startAt(), locked)) {
                offerFree(r.roomId(), r.startAt(), r.endAt(), reservationId, locked, stored);
            }
        });
        inbox.publish(stored);
    }

    @Override
    public int advance(Instant now) {
        List<Notification> stored = new ArrayList<>();
        Integer passed = tx.execute(s -> {
            port.lockAdvance();  // advance끼리 한 줄로: 여러 방 잠금을 서로 다른 순서로 잡아 생기는 교착 방지
            // 받기(예약 저장)는 방 잠금 → 넘김 행 순서. 받기가 다른 방 넘김 행을 기다리는 동안 여기서 그 행을 쥐고
            // 그 방 잠금을 기다리면 교착이라, 손댈 방을 먼저 모두(방 번호 순) 잠근 뒤에 행을 바꾼다
            Instant cutoff = clock.instant().plus(Duration.ofMinutes(relay.stopBeforeMinutes()));
            List<Integer> toLock = port.roomsToAdvance(now, cutoff);
            toLock.forEach(port::lockRoom);
            Set<Integer> rooms = new HashSet<>(toLock);
            for (int roomId : port.roomsWithExpiredOffers(now)) {
                if (rooms.contains(roomId)) {
                    port.expireOffers(roomId, now);
                }
            }
            int n = 0;
            // 잠근 방의 것만 (방을 잠근 뒤 다른 방에 새로 생긴 것은 다음 advance가 한다)
            for (OfferRow done : port.offersToPass(rooms)) {
                port.markPassed(done.id());
                Instant locked = clock.instant();  // 잠금 뒤 시각으로 마감·5분을 잼
                // 받은(TAKEN) 넘김은 받은 예약이 안 덮은 남은 칸만, 넘김·시간 지남은 구간 전체가 대상
                n += offerFree(done.roomId(), done.startAt(), done.endAt(), done.sourceReservationId(), locked,
                        stored);
            }
            // 취소 뒤 첫 넘기기가 실패한 것도 여기서 다시 (줄 선 사람이 있는데 넘김이 한 번도 없는 취소)
            for (long reservationId : port.unofferedCancellations(cutoff)) {
                Released r = port.canceledReservation(reservationId).orElse(null);
                if (r == null || !rooms.contains(r.roomId())) {
                    continue;
                }
                Instant locked = clock.instant();
                if (!open(r.startAt(), locked)) {
                    continue;
                }
                n += offerFree(r.roomId(), r.startAt(), r.endAt(), reservationId, locked, stored);
            }
            return n;
        });
        inbox.publish(stored);
        return Objects.requireNonNullElse(passed, 0);
    }

    /**
     * 구간 안에서 아직 아무도 잡지 않은 부분마다 줄 첫 사람에게 넘긴다. 방 잠금 안에서 부른다.
     * 보통은 구간 전체 하나. 일부만 받았으면(14:00–15:00 중 14:00–14:30) 남은 14:30–15:00을 그 칸 기다린 사람에게
     */
    private int offerFree(int roomId, Instant start, Instant end, long sourceReservationId, Instant now,
                          List<Notification> stored) {
        int n = 0;
        Instant from = start;
        List<Span> busy = new ArrayList<>(port.busy(roomId, start, end));
        busy.add(new Span(end, end));
        for (Span b : busy) {
            Instant to = b.start().isBefore(end) ? b.start() : end;
            if (from.isBefore(to) && open(from, now)) {
                Optional<OfferRow> next = port.offerToNext(roomId, from, to, sourceReservationId, now, expiry(now));
                if (next.isPresent()) {
                    stored.addAll(notify(next.get(), now));
                    n++;
                }
            }
            if (b.end().isAfter(from)) {
                from = b.end();
            }
        }
        return n;
    }

    /** 아직 넘길 수 있는 때인지: 시작 stopBeforeMinutes분 전보다 이르다 */
    private boolean open(Instant startAt, Instant now) {
        return now.isBefore(startAt.minus(Duration.ofMinutes(relay.stopBeforeMinutes())));
    }

    private Instant expiry(Instant now) {
        return now.plus(Duration.ofMinutes(relay.holdMinutes()));
    }

    private List<Notification> notify(OfferRow o, Instant now) {
        String body = "%s %s–%s 자리가 났어요. %d분 안에 받으면 예약할 수 있어요. 못 쓰면 [넘길게요]를 눌러 주세요."
                .formatted(port.roomName(o.roomId()), DAY.format(o.startAt().atZone(SEOUL)),
                        TIME.format(o.endAt().atZone(SEOUL)), relay.holdMinutes());
        return inbox.store(List.of(new NewNotification(o.userId(), null, KIND, "기다리던 자리가 났어요", body)), now);
    }

    private static Wait wait(WaitRow w) {
        return new Wait(w.id(), w.roomId(), w.startAt().atZone(SEOUL), w.order());
    }

    private static Offer offer(OfferRow o) {
        return new Offer(o.id(), o.roomId(), o.startAt().atZone(SEOUL), o.endAt().atZone(SEOUL),
                o.expiresAt().atZone(SEOUL), o.order());
    }
}
