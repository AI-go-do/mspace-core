package kr.ac.chosun.mspace.reservation.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.chosun.mspace.common.config.RelayChancePolicy;
import kr.ac.chosun.mspace.common.config.RelayPolicy;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.RelayChancePort.Booked;
import kr.ac.chosun.mspace.reservation.application.RelayChancePort.SlotHistory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 읽기만 해서 잠금이 없다. 가능성을 보고 기다리기를 거는 사이에 칸이 바뀌어도 기다리기 API가 다시 확인한다.
 * 지난 기록은 하루 최대 5방 × 48칸이라 매번 세도 작다.
 */
@Service
class RelayChanceService implements RelayChanceUseCase {

    private final RelayChancePort port;
    private final RelayChancePolicy chance;
    private final RelayPolicy relay;
    private final ReservationPolicy reservation;
    private final Clock clock;

    RelayChanceService(RelayChancePort port, RelayChancePolicy chance, RelayPolicy relay,
                       ReservationPolicy reservation, Clock clock) {
        this.port = port;
        this.chance = chance;
        this.relay = relay;
        this.reservation = reservation;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Chance> chances(long userId, LocalDate date) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, SEOUL);
        if (date.isBefore(today) || !date.isBefore(today.plusDays(reservation.bookingWindowDays()))) {
            throw new BusinessException(ErrorCode.ROOM_DATE_OUT_OF_RANGE);
        }
        Instant dayStart = date.atStartOfDay(SEOUL).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SEOUL).toInstant();
        // 시작 30분 전이 지난 예약은 취소돼도 릴레이로 넘어오지 않으니 뺀다
        Instant from = now.plus(Duration.ofMinutes(relay.stopBeforeMinutes()));
        List<Booked> booked = port.bookedOthers(dayStart, dayEnd, from, userId);
        if (booked.isEmpty()) {
            return List.of();
        }
        Map<Integer, SlotHistory> history = port
                .history(now.minus(Duration.ofDays(7L * chance.historyWeeks())), now, relay.stopBeforeMinutes())
                .stream().collect(Collectors.toMap(h -> key(h.dayOfWeek(), h.hour()), Function.identity()));

        List<Chance> out = new ArrayList<>();
        for (Booked b : booked) {
            ZonedDateTime start = b.startAt().atZone(SEOUL);
            SlotHistory h = history.get(key(start.getDayOfWeek().getValue(), start.getHour()));
            CancelChance.Estimate e = CancelChance.estimate(h == null ? 0 : h.canceled(), h == null ? 0 : h.total(),
                    Duration.between(b.createdAt(), b.startAt()), chance);
            out.add(new Chance(b.roomId(), start, b.endAt().atZone(SEOUL), e.percent(), e.reasons(),
                    port.waiting(b.roomId(), b.startAt(), b.endAt())));
        }
        out.sort(Comparator.comparingInt(Chance::percent).reversed()
                .thenComparing(Chance::startAt).thenComparingInt(Chance::roomId));
        return out;
    }

    private static int key(int dayOfWeek, int hour) {
        return dayOfWeek * 100 + hour;
    }
}
