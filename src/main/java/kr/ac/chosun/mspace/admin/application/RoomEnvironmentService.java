package kr.ac.chosun.mspace.admin.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.chosun.mspace.admin.application.RoomEnvironmentPort.Booking;
import kr.ac.chosun.mspace.admin.application.RoomEnvironmentPort.Latest;
import kr.ac.chosun.mspace.admin.application.RoomEnvironmentPort.MinuteRow;
import kr.ac.chosun.mspace.admin.application.RoomEnvironmentPort.PresenceRow;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 "지금" 탭. 화면이 10초마다 부른다.
 * <ul>
 *   <li>저장 표를 따로 두지 않고 sensor_event를 바로 읽는다. 방 5개 × 60분 = 300줄이라 V11 인덱스
 *       (room_id, kind, observed_at DESC) 범위 읽기로 충분하다</li>
 *   <li>재실·끊김은 방 현황(GET /api/rooms/status)과 같은 기준(offline-after-seconds). 끊긴 방을 "비어 있음"으로 보이지 않는다</li>
 *   <li>1분 칸은 서울 시계와 상관없이 UTC 분 경계로 자른다(분 단위라 같다). 지금 진행 중인 분까지 넣는다</li>
 * </ul>
 */
@Service
class RoomEnvironmentService implements RoomEnvironmentUseCase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RoomEnvironmentPort port;
    private final SensorPolicy sensor;
    private final Clock clock;

    RoomEnvironmentService(RoomEnvironmentPort port, SensorPolicy sensor, Clock clock) {
        this.port = port;
        this.sensor = sensor;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public RoomsEnvironment environment(long adminId, Integer minutes) {
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        int m = minutes == null ? DEFAULT_MINUTES : minutes;
        if (m < 1 || m > MAX_MINUTES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "minutes는 1–" + MAX_MINUTES + " 사이여야 합니다");
        }
        Instant now = clock.instant();
        Instant to = now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
        Instant from = to.minus(Duration.ofMinutes(m));
        Instant aliveAfter = now.minusSeconds(sensor.offlineAfterSeconds());

        Map<Integer, PresenceRow> presence = port.presence().stream()
                .collect(Collectors.toMap(PresenceRow::roomId, Function.identity()));
        Map<Integer, Latest> latest = port.latest().stream()
                .collect(Collectors.toMap(Latest::roomId, Function.identity()));
        Map<Integer, List<MinuteRow>> series = port.series(from, to).stream()
                .collect(Collectors.groupingBy(MinuteRow::roomId));
        List<Booking> bookings = port.bookingsEndingAfter(now);

        List<RoomEnvironment> rooms = port.rooms().stream().map(room -> {
            PresenceRow p = presence.get(room.id());
            Latest l = latest.get(room.id());
            Instant presenceAt = p == null || p.lastReceivedAt().equals(Instant.EPOCH) ? null : p.lastReceivedAt();
            Instant lastSignal = later(presenceAt, l == null ? null : l.lastReceivedAt());
            String seen = presenceAt == null || !presenceAt.isAfter(aliveAfter) ? "UNKNOWN"
                    : p.occupied() ? "OCCUPIED" : "EMPTY";
            Booking current = bookings.stream()
                    .filter(b -> b.roomId() == room.id() && !now.isBefore(b.startAt()) && now.isBefore(b.endAt()))
                    .findFirst().orElse(null);
            Booking next = bookings.stream()
                    .filter(b -> b.roomId() == room.id() && b.startAt().isAfter(now))
                    .findFirst().orElse(null);
            return new RoomEnvironment(room.id(), room.name(), seen, seoul(lastSignal),
                    lastSignal == null || !lastSignal.isAfter(aliveAfter),
                    l == null || l.ppm() == null ? null : new Co2(l.ppm(), seoul(l.ppmAt())),
                    l == null ? null : l.temperatureC(), l == null ? null : l.humidityPct(),
                    series.getOrDefault(room.id(), List.of()).stream()
                            .map(r -> new Point(seoul(r.at()), r.ppm(), r.temperatureC(), r.humidityPct())).toList(),
                    current == null ? null : new Current(current.id(), seoul(current.startAt()), seoul(current.endAt()),
                            current.headcount()),
                    next == null ? null : new Next(seoul(next.startAt()), next.headcount()));
        }).toList();
        return new RoomsEnvironment(seoul(now), m, rooms);
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static ZonedDateTime seoul(Instant at) {
        return at == null ? null : at.atZone(SEOUL);
    }
}
