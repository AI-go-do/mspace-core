package kr.ac.chosun.mspace.reservation.adapter.in.web;

import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.reservation.application.StillHereUseCase;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StillHereController {

    private final StillHereUseCase stillHere;

    StillHereController(StillHereUseCase stillHere) {
        this.stillHere = stillHere;
    }

    /**
     * "아직 있어요" (FR-DEV-103, 시연·테스트 전용). 대표자, 사용 중(IN_USE), 예약당 1번. 자리 비움 타이머를 지금부터 다시 잰다.
     * 200 {"id":12,"status":"IN_USE"} / 404 꺼져 있음(STILL_HERE_DISABLED)·없거나 내 예약 아님 / 403 참여자
     * / 409 사용 중 아님(RESERVATION_NOT_IN_USE)·이미 씀(STILL_HERE_ALREADY_USED)
     */
    @PostMapping("/api/reservations/{id}/still-here")
    StatusChangeResponse stillHere(LoginUser user, @PathVariable long id) {
        stillHere.stillHere(id, user.id());
        return new StatusChangeResponse(id, ReservationStatus.IN_USE.name());
    }
}
