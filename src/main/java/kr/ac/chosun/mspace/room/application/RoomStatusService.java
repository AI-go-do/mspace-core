package kr.ac.chosun.mspace.room.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityPort.BlockedTime;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityPort.RoomInfo;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityService.SlotBlock;
import kr.ac.chosun.mspace.room.application.RoomStatusPort.ActiveReservation;
import kr.ac.chosun.mspace.room.application.RoomStatusPort.PresenceRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-DEV-102 방 현황: 지금 비었는지, 다음 예약 시각, 센서가 본 재실.
 * <ul>
 *   <li>"비었는지"(status)는 예약 기준이다. 예약을 잡을 수 있는지와 같은 기준이라 화면·비서가 이것으로 안내한다</li>
 *   <li>센서 재실(presence)은 참고로 따로 준다. 예약은 없는데 사람이 있거나, 사용 중인데 비어 있는 것을 보여 주려고</li>
 *   <li>센서 신호가 끊겼거나(offline-after-seconds) 한 번도 안 왔으면 UNKNOWN. 모르는 것을 "비어 있음"으로 보이지 않는다</li>
 * </ul>
 * 로그인 없이 볼 수 있다(빈 시간 조회와 같음). 누가 예약했는지는 내려 주지 않는다.
 */
@Service
public class RoomStatusService {

    private final RoomAvailabilityPort rooms;
    private final RoomStatusPort port;
    private final SensorPolicy sensor;
    private final Clock clock;

    public RoomStatusService(RoomAvailabilityPort rooms, RoomStatusPort port, SensorPolicy sensor, Clock clock) {
        this.rooms = rooms;
        this.port = port;
        this.sensor = sensor;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RoomsStatus getStatus() {
        Instant now = clock.instant();
        List<ActiveReservation> active = port.findReservationsEndingAfter(now);
        List<BlockedTime> blocks = rooms.findActiveBlocks(now, now.plusNanos(1));
        Map<Integer, PresenceRow> presence = port.findPresence().stream()
                .collect(Collectors.toMap(PresenceRow::roomId, Function.identity()));
        Instant aliveAfter = now.minusSeconds(sensor.offlineAfterSeconds());

        List<RoomStatus> result = new ArrayList<>();
        for (RoomInfo room : rooms.findActiveRooms()) {
            ActiveReservation current = active.stream()
                    .filter(r -> r.roomId() == room.id() && !now.isBefore(r.startAt()) && now.isBefore(r.endAt()))
                    .findFirst().orElse(null);
            Instant nextStart = active.stream()
                    .filter(r -> r.roomId() == room.id() && r.startAt().isAfter(now))
                    .map(ActiveReservation::startAt)
                    .min(Instant::compareTo).orElse(null);

            SlotBlock block = RoomAvailabilityService.blockOf(blocks, room.id(), now, now.plusNanos(1));
            Status status = current != null ? (current.inUse() ? Status.IN_USE : Status.RESERVED)
                    : block != null ? Status.BLOCKED : Status.AVAILABLE;
            PresenceRow p = presence.get(room.id());
            Presence seen = p == null || !p.lastReceivedAt().isAfter(aliveAfter) ? Presence.UNKNOWN
                    : p.occupied() ? Presence.OCCUPIED : Presence.EMPTY;

            result.add(new RoomStatus(room.id(), room.name(), room.minPeople(), room.maxPeople(), status,
                    current == null ? null : seoul(current.startAt()),
                    current == null ? null : seoul(current.endAt()),
                    seoul(nextStart), seen, block));
        }
        int available = (int) result.stream().filter(r -> r.status() == Status.AVAILABLE).count();
        return new RoomsStatus(seoul(now), available, result.size(), result);
    }

    private static ZonedDateTime seoul(Instant at) {
        return at == null ? null : at.atZone(SEOUL);
    }

    /** 예약 기준 지금 상태 */
    public enum Status {
        /** 지금 걸린 예약이 없다. 바로 잡을 수 있다 */
        AVAILABLE,
        /** 예약 시간이지만 아직 입실 전 (노쇼 마감 전) */
        RESERVED,
        /** 입실해서 사용 중 */
        IN_USE,
        /** 걸린 예약은 없지만 관리자가 막아 둠 (공사·점검·대관, Q-23). 사유는 block */
        BLOCKED
    }

    /** 센서가 본 재실 */
    public enum Presence {
        OCCUPIED, EMPTY,
        /** 센서 신호가 끊겼거나 한 번도 안 옴 */
        UNKNOWN
    }

    /**
     * @param availableCount 지금 예약 기준으로 빈 방 수 (홈 "지금 비어 있는 방 N개 중 M개")
     */
    public record RoomsStatus(ZonedDateTime now, int availableCount, int roomCount, List<RoomStatus> rooms) {
    }

    /**
     * @param currentStartAt 지금 걸린 예약의 시작. 없으면 null
     * @param currentEndAt   지금 걸린 예약의 끝 (언제 비는지). 없으면 null
     * @param nextStartAt    지금 이후 가장 가까운 예약 시작. 없으면 null
     * @param block          지금 이 방을 막고 있는 관리자 막기 (사유·메모·끝). 없으면 null
     */
    public record RoomStatus(int roomId, String roomName, int minPeople, int maxPeople, Status status,
                             ZonedDateTime currentStartAt, ZonedDateTime currentEndAt, ZonedDateTime nextStartAt,
                             Presence presence, SlotBlock block) {
    }
}
