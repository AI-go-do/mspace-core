package kr.ac.chosun.mspace.room.adapter.in.web;

import kr.ac.chosun.mspace.room.application.RoomStatusService;
import kr.ac.chosun.mspace.room.application.RoomStatusService.RoomsStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RoomStatusController {

    private final RoomStatusService service;

    RoomStatusController(RoomStatusService service) {
        this.service = service;
    }

    /** FR-DEV-102 방 현황. 예: GET /api/rooms/status */
    @GetMapping("/api/rooms/status")
    RoomsStatus status() {
        return service.getStatus();
    }
}
