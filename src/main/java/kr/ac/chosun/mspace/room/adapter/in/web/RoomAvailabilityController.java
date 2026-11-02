package kr.ac.chosun.mspace.room.adapter.in.web;

import java.time.LocalDate;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityService;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityService.DayAvailability;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RoomAvailabilityController {

    private final RoomAvailabilityService service;

    RoomAvailabilityController(RoomAvailabilityService service) {
        this.service = service;
    }

    /** 예: GET /api/rooms/availability?date=2026-11-03 */
    @GetMapping("/api/rooms/availability")
    DayAvailability availability(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.getAvailability(date);
    }
}
