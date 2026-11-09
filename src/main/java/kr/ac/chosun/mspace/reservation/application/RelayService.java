package kr.ac.chosun.mspace.reservation.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
                port.offerToNext(r.roomId(), r.startAt(), r.endAt(), reservationId, locked, expiry(locked))
                        .ifPresent(o -> stored.addAll(notify(o, locked)));
            }
        });
        inbox.publish(stored);
    }

    @Override
    public int advance(Instant now) {
        List<Notification> stored = new ArrayList<>();
        Integer passed = tx.execute(s -> {
            port.expireOffers(now);
            int n = 0;
            for (OfferRow done : port.offersToPass()) {
                port.lockRoom(done.roomId());
                port.markPassed(done.id());
                Instant locked = clock.instant();  // 잠금 뒤 시각으로 마감·5분을 잼
                if (!open(done.startAt(), locked)) {
                    continue;
                }
                Optional<OfferRow> next = port.offerToNext(done.roomId(), done.startAt(), done.endAt(),
                        done.sourceReservationId(), locked, expiry(locked));
                if (next.isPresent()) {
                    stored.addAll(notify(next.get(), locked));
                    n++;
                }
            }
            return n;
        });
        inbox.publish(stored);
        return passed == null ? 0 : passed;
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
