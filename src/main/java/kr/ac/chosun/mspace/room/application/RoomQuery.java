package kr.ac.chosun.mspace.room.application;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 다른 기능(예약 등)이 방 정보를 볼 때 쓰는 공개 창구. 팀 컨벤션 "기능끼리는 서로의 application만 부른다".
 * "사용 가능한 방 = is_active" 규칙은 room 모듈 안(JdbcRoomAvailabilityAdapter)에만 둔다.
 */
@Service
public class RoomQuery {

    private final RoomAvailabilityPort rooms;

    public RoomQuery(RoomAvailabilityPort rooms) {
        this.rooms = rooms;
    }

    /** 사용 가능한(is_active) 방. 없거나 사용 중지면 빈 값 */
    public Optional<ActiveRoom> findActive(int roomId) {
        return rooms.findActiveRoom(roomId)
                .map(r -> new ActiveRoom(r.id(), r.name(), r.minPeople(), r.maxPeople()));
    }

    /** 방 이름과 이용 인원 범위 */
    public record ActiveRoom(int id, String name, int minPeople, int maxPeople) {
    }
}
