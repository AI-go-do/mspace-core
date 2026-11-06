package kr.ac.chosun.mspace.room.adapter.in.web;

import java.util.List;
import kr.ac.chosun.mspace.room.application.RoomListService;
import kr.ac.chosun.mspace.room.application.RoomListService.RoomSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RoomListController {

    private final RoomListService service;

    RoomListController(RoomListService service) {
        this.service = service;
    }

    /** 예: GET /api/rooms → [{"id":1,"name":"1번 방","minPeople":3,"maxPeople":5}, ...] */
    @GetMapping("/api/rooms")
    List<RoomSummary> rooms() {
        return service.listRooms();
    }
}
