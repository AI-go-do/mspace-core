package kr.ac.chosun.mspace.room.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 빈 시간 계산에 필요한 데이터를 DB에서 가져오는 창구(포트). 구현은 adapter/out. */
public interface RoomAvailabilityPort {

    /** 사용 가능한(is_active) 방, 번호 순 */
    List<RoomInfo> findActiveRooms();

    /** 사용 가능한(is_active) 방 하나. 없거나 사용 중지면 빈 값 */
    Optional<RoomInfo> findActiveRoom(int roomId);

    /** [from, to)와 겹치는 끝나지 않은(BOOKED, IN_USE) 예약의 시간 */
    List<BookedTime> findActiveReservations(Instant from, Instant to);

    record RoomInfo(int id, String name, int minPeople, int maxPeople) {
    }

    record BookedTime(int roomId, Instant startAt, Instant endAt) {
    }
}
