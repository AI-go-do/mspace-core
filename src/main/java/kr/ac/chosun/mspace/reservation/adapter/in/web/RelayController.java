package kr.ac.chosun.mspace.reservation.adapter.in.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.RelayChanceUseCase;
import kr.ac.chosun.mspace.reservation.application.RelayChanceUseCase.Chance;
import kr.ac.chosun.mspace.reservation.application.RelayUseCase;
import kr.ac.chosun.mspace.reservation.application.RelayUseCase.Offer;
import kr.ac.chosun.mspace.reservation.application.RelayUseCase.Wait;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 빈방 릴레이 (Q-18). 받기는 따로 없고 보통 예약 만들기(POST /api/reservations)에 relayOfferId를 붙인다.
 */
@RestController
class RelayController {

    private final RelayUseCase relay;
    private final RelayChanceUseCase chances;

    RelayController(RelayUseCase relay, RelayChanceUseCase chances) {
        this.relay = relay;
        this.chances = chances;
    }

    /**
     * 기다릴 칸 고르기: 그날 차 있는 예약이 시작 30분 전까지 취소될 가능성(%). 예: GET /api/relay/chances?date=2030-03-05
     * 200 [{"roomId":1,"startAt":…,"endAt":…,"percent":20,"reasons":["BOOKED_EARLY"],"waiting":2}] (높은 순)
     * / 400 오늘부터 예약 가능한 기간 밖(ROOM_DATE_OUT_OF_RANGE)
     */
    @GetMapping("/api/relay/chances")
    List<Chance> chances(LoginUser user, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return chances.chances(user.id(), date);
    }

    /** 내 기다리기. 200 [{"id":3,"roomId":2,"startAt":"…+09:00","order":1}] */
    @GetMapping("/api/relay/waits/me")
    List<Wait> myWaits(LoginUser user) {
        return relay.myWaits(user.id());
    }

    /**
     * 차 있는 30분 칸에 기다리기 걸기. 예: {"roomId":2,"startAt":"2030-03-05T14:00:00+09:00"}
     * 200 기다리기(같은 칸에 이미 걸었으면 그것) / 400 칸 시작이 아님 / 404 방 없음
     * / 409 비어 있음(RELAY_SLOT_FREE)·3개 넘음(RELAY_WAIT_LIMIT)·시작 30분 전부터(RELAY_TOO_LATE)
     */
    @PostMapping("/api/relay/waits")
    Wait addWait(LoginUser user, @RequestBody WaitRequest request) {
        if (request == null || request.roomId() == null || request.startAt() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return relay.addWait(user.id(), request.roomId(), request.startAt().toInstant());
    }

    /** 그만 기다리기. 204 / 404 없거나 이미 끝남 */
    @PostMapping("/api/relay/waits/{id}/cancel")
    ResponseEntity<Void> cancelWait(LoginUser user, @PathVariable long id) {
        relay.cancelWait(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** 나에게 넘어온 자리 (받을 시간이 남은 것). 200 [{"id":7,"roomId":2,"startAt":…,"endAt":…,"expiresAt":…,"order":1}] */
    @GetMapping("/api/relay/offers/me")
    List<Offer> myOffers(LoginUser user) {
        return relay.myOffers(user.id());
    }

    /** 넘길게요 → 다음 사람. 204 / 404 내 것이 아니거나 이미 끝남 */
    @PostMapping("/api/relay/offers/{id}/decline")
    ResponseEntity<Void> decline(LoginUser user, @PathVariable long id) {
        relay.decline(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    record WaitRequest(Integer roomId, OffsetDateTime startAt) {
    }
}
