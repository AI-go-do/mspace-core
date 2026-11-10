package kr.ac.chosun.mspace.tidy.adapter.in.web;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.reservation.domain.AccessQr;
import kr.ac.chosun.mspace.tidy.application.RetrievePassUseCase;
import kr.ac.chosun.mspace.tidy.application.RetrievePassUseCase.Pass;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RetrievePassController {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RetrievePassUseCase passes;

    RetrievePassController(RetrievePassUseCase passes) {
        this.passes = passes;
    }

    /**
     * "찾으러 갈게요" (FR-DEV-105, 11/10 지영). 두고 간 물건 알림을 받은 대표자·참여자가 퇴실 뒤 3시간 안에 누른다.
     * 200 {"reservationId":12,"roomId":1,"code":"123456","qrText":"MSPACE:1:123456","expiresAt":"…+09:00"}.
     * 10분 안에 다시 누르면 같은 QR. 문 앞 POST /api/access/verify는 {"result":"OPENED","purpose":"RETRIEVE"}로 답한다.
     * 404 없거나 내 예약 아님(RESERVATION_NOT_FOUND) / 409 끝나지 않음(RESERVATION_NOT_ENDED)
     * ·두고 간 물건 없음(RETRIEVE_NOT_NEEDED)·퇴실 뒤 3시간 지남(RETRIEVE_TOO_LATE)
     */
    @PostMapping("/api/reservations/{id}/retrieve-pass")
    PassResponse issue(LoginUser user, @PathVariable long id) {
        Pass p = passes.issue(id, user.id());
        return new PassResponse(p.reservationId(), p.roomId(), p.code(), AccessQr.text(p.roomId(), p.code()),
                p.expiresAt().atZone(SEOUL));
    }

    record PassResponse(long reservationId, int roomId, String code, String qrText, ZonedDateTime expiresAt) {
    }
}
