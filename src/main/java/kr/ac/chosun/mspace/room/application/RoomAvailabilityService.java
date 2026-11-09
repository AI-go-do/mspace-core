package kr.ac.chosun.mspace.room.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityPort.BlockedTime;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityPort.BookedTime;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityPort.RoomInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하루(서울 00:00~24:00)를 30분 칸으로 나눠 방마다 비었는지 알려 준다.
 * 관리자가 막은 칸(Q-23)은 available=false에 block으로 사유를 붙인다.
 * 보여 주기용이다. 실제로 잡을 수 있는지는 예약 생성 때 DB 겹침 제약(no_room_overlap)이 최종 판단한다.
 */
@Service
public class RoomAvailabilityService {

    private final RoomAvailabilityPort port;
    private final ReservationPolicy policy;
    private final Clock clock;

    public RoomAvailabilityService(RoomAvailabilityPort port, ReservationPolicy policy, Clock clock) {
        this.port = port;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DayAvailability getAvailability(LocalDate date) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, SEOUL);
        // 예약 생성과 같은 범위: 오늘 ~ 오늘 + (기간-1)일. 그 밖은 볼 이유가 없어서 400
        if (date.isBefore(today) || !date.isBefore(today.plusDays(policy.bookingWindowDays()))) {
            throw new BusinessException(ErrorCode.ROOM_DATE_OUT_OF_RANGE);
        }

        ZonedDateTime dayStart = date.atStartOfDay(SEOUL);
        ZonedDateTime dayEnd = date.plusDays(1).atStartOfDay(SEOUL);
        List<BookedTime> booked = port.findActiveReservations(dayStart.toInstant(), dayEnd.toInstant());
        List<BlockedTime> blocks = port.findActiveBlocks(dayStart.toInstant(), dayEnd.toInstant());
        Duration slot = Duration.ofMinutes(policy.slotMinutes());

        List<RoomSlots> rooms = new ArrayList<>();
        for (RoomInfo room : port.findActiveRooms()) {
            List<Slot> slots = new ArrayList<>();
            for (ZonedDateTime s = dayStart; s.isBefore(dayEnd); s = s.plus(slot)) {
                ZonedDateTime e = s.plus(slot);
                boolean past = s.toInstant().isBefore(now);
                boolean taken = overlaps(booked, room.id(), s.toInstant(), e.toInstant());
                SlotBlock block = blockOf(blocks, room.id(), s.toInstant(), e.toInstant());
                slots.add(new Slot(s, e, !past && !taken && block == null, block));
            }
            // 지금 시각이 든 칸은 지난 칸이라 available=false로만 나와서 예약 여부를 알 수 없다 → 따로 알려 준다
            SlotBlock blockedNow = date.equals(today) ? blockOf(blocks, room.id(), now, now.plusNanos(1)) : null;
            boolean occupiedNow = date.equals(today) && (occupiedAt(booked, room.id(), now) || blockedNow != null);
            rooms.add(new RoomSlots(room.id(), room.name(), room.minPeople(), room.maxPeople(), occupiedNow,
                    blockedNow, slots));
        }
        return new DayAvailability(date, policy.slotMinutes(), rooms);
    }

    /** 이 시각에 이 방의 끝나지 않은 예약이 있는지. 반열린 구간 [시작, 끝) 기준 */
    private static boolean occupiedAt(List<BookedTime> booked, int roomId, Instant at) {
        return booked.stream().anyMatch(b -> b.roomId() == roomId
                && !at.isBefore(b.startAt()) && at.isBefore(b.endAt()));
    }

    /** 이 칸 [from, to)과 겹치는 이 방의 막기. 없으면 null */
    static SlotBlock blockOf(List<BlockedTime> blocks, int roomId, Instant from, Instant to) {
        return blocks.stream()
                .filter(b -> b.roomId() == roomId && b.startAt().isBefore(to) && from.isBefore(b.endAt()))
                .findFirst()
                .map(b -> new SlotBlock(b.reason(), b.memo(), b.endAt().atZone(SEOUL)))
                .orElse(null);
    }

    /** 반열린 구간 [시작, 끝)끼리 겹치는지. DB의 tstzrange && 와 같은 기준 */
    private static boolean overlaps(List<BookedTime> booked, int roomId, Instant from, Instant to) {
        return booked.stream().anyMatch(b -> b.roomId() == roomId
                && b.startAt().isBefore(to) && from.isBefore(b.endAt()));
    }

    public record DayAvailability(LocalDate date, int slotMinutes, List<RoomSlots> rooms) {
    }

    /**
     * @param occupiedNow 지금 이 방에 끝나지 않은 예약이 있거나 막혀 있는지. 오늘이 아닌 날짜면 false
     * @param blockedNow  지금 이 방을 막고 있는 관리자 막기. 없거나 오늘이 아니면 null
     */
    public record RoomSlots(int roomId, String roomName, int minPeople, int maxPeople, boolean occupiedNow,
                            SlotBlock blockedNow, List<Slot> slots) {
    }

    /** @param block 관리자가 막은 칸이면 그 사유 (available은 false). 아니면 null */
    public record Slot(ZonedDateTime startAt, ZonedDateTime endAt, boolean available, SlotBlock block) {
    }

    /** 막힌 칸·방의 사유. memo는 관리자가 학생에게 보이려고 적은 짧은 말, endAt은 막힌 기간이 끝나는 시각 */
    public record SlotBlock(BlockReason reason, String memo, ZonedDateTime endAt) {
    }
}
