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
import kr.ac.chosun.mspace.room.application.RoomAvailabilityPort.BookedTime;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityPort.RoomInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하루(서울 00:00~24:00)를 30분 칸으로 나눠 방마다 비었는지 알려 준다.
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
        Duration slot = Duration.ofMinutes(policy.slotMinutes());

        List<RoomSlots> rooms = new ArrayList<>();
        for (RoomInfo room : port.findActiveRooms()) {
            List<Slot> slots = new ArrayList<>();
            for (ZonedDateTime s = dayStart; s.isBefore(dayEnd); s = s.plus(slot)) {
                ZonedDateTime e = s.plus(slot);
                boolean past = s.toInstant().isBefore(now);
                boolean taken = overlaps(booked, room.id(), s.toInstant(), e.toInstant());
                slots.add(new Slot(s, e, !past && !taken));
            }
            rooms.add(new RoomSlots(room.id(), room.name(), room.minPeople(), room.maxPeople(), slots));
        }
        return new DayAvailability(date, policy.slotMinutes(), rooms);
    }

    /** 반열린 구간 [시작, 끝)끼리 겹치는지. DB의 tstzrange && 와 같은 기준 */
    private static boolean overlaps(List<BookedTime> booked, int roomId, Instant from, Instant to) {
        return booked.stream().anyMatch(b -> b.roomId() == roomId
                && b.startAt().isBefore(to) && from.isBefore(b.endAt()));
    }

    public record DayAvailability(LocalDate date, int slotMinutes, List<RoomSlots> rooms) {
    }

    public record RoomSlots(int roomId, String roomName, int minPeople, int maxPeople, List<Slot> slots) {
    }

    public record Slot(ZonedDateTime startAt, ZonedDateTime endAt, boolean available) {
    }
}
