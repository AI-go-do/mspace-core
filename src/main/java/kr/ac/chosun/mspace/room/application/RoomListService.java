package kr.ac.chosun.mspace.room.application;

import java.util.List;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityPort.RoomInfo;
import org.springframework.stereotype.Service;

/** FR-ROOM-001 방 목록. 사용 불가(is_active=false) 방은 빼고 번호 순으로 준다. */
@Service
public class RoomListService {

    private final RoomAvailabilityPort rooms;

    public RoomListService(RoomAvailabilityPort rooms) {
        this.rooms = rooms;
    }

    public List<RoomSummary> listRooms() {
        return rooms.findActiveRooms().stream().map(RoomSummary::from).toList();
    }

    /** 화면에 보여 줄 방 한 개. 인원 숫자는 코드가 아니라 rooms 테이블에서 온다 */
    public record RoomSummary(int id, String name, int minPeople, int maxPeople) {
        static RoomSummary from(RoomInfo r) {
            return new RoomSummary(r.id(), r.name(), r.minPeople(), r.maxPeople());
        }
    }
}
