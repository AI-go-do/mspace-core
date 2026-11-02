package kr.ac.chosun.mspace.reservation.adapter.in.web;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.CreateReservationService;
import kr.ac.chosun.mspace.reservation.application.CreateReservationService.Command;
import kr.ac.chosun.mspace.reservation.application.CreateReservationService.Created;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReservationController {

    private final CreateReservationService service;

    ReservationController(CreateReservationService service) {
        this.service = service;
    }

    /**
     * 예: POST /api/reservations
     * {"roomId":1,"startAt":"2026-11-03T14:00:00+09:00","endAt":"2026-11-03T16:00:00+09:00",
     *  "participantStudentIds":["20230002","20230003"]}   ← 대표자 본인은 빼고 적는다
     */
    @PostMapping("/api/reservations")
    ResponseEntity<Created> create(LoginUser user, @RequestBody CreateReservationRequest request) {
        Created created = service.create(user.id(), request.toCommand());
        return ResponseEntity.created(URI.create("/api/reservations/" + created.id())).body(created);
    }

    record CreateReservationRequest(Integer roomId, OffsetDateTime startAt, OffsetDateTime endAt,
                                    List<String> participantStudentIds) {

        Command toCommand() {
            if (roomId == null || startAt == null || endAt == null || participantStudentIds == null
                    || participantStudentIds.stream().anyMatch(s -> s == null || s.isBlank())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            return new Command(roomId, startAt.toInstant(), endAt.toInstant(),
                    participantStudentIds.stream().map(String::trim).toList());
        }
    }
}
