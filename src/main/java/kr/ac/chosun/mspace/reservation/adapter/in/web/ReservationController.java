package kr.ac.chosun.mspace.reservation.adapter.in.web;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.CancelReservationUseCase;
import kr.ac.chosun.mspace.reservation.application.CancelReservationUseCase.Canceled;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Command;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Created;
import kr.ac.chosun.mspace.reservation.application.MyReservationsUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 예약 API 입구. 요청 모양만 확인하고, 예약 규칙은 입력 포트(…UseCase)가 본다 */
@RestController
class ReservationController {

    private final CreateReservationUseCase service;
    private final MyReservationsUseCase myReservations;
    private final CancelReservationUseCase cancels;

    ReservationController(CreateReservationUseCase service, MyReservationsUseCase myReservations,
                          CancelReservationUseCase cancels) {
        this.service = service;
        this.myReservations = myReservations;
        this.cancels = cancels;
    }

    /**
     * 미리 예약을 만든다 (FR-RSV-001). 성공하면 201과 Location 헤더(/api/reservations/{id})를 돌려준다.
     * 예: POST /api/reservations
     * {"roomId":1,"startAt":"2026-11-03T14:00:00+09:00","endAt":"2026-11-03T16:00:00+09:00",
     *  "participantStudentIds":["20230002","20230003"]}   ← 대표자 본인은 빼고 적는다
     *
     * <p>
     * Idempotency-Key 헤더(64자 이하)를 붙이면, 같은 키로 다시 보낼 때 새로 만들지 않고 처음 예약을 200으로 돌려준다
     * (비서가 응답을 못 받아 다시 보내도 예약이 두 번 생기지 않게). 같은 키를 다른 내용에 쓰면 409(IDEMPOTENCY_KEY_REUSED).
     *
     * @param user           로그인한 회원 (대표자가 된다)
     * @param idempotencyKey 멱등 키. 없으면 매번 새로 만든다 (웹 화면)
     * @param request        방·시작·끝 시각·참여자 학번
     * @return 만든 예약 (키패드 번호 포함, 대표자에게만 보여 준다). 새로 만들면 201, 같은 키 재요청이면 200
     */
    @PostMapping("/api/reservations")
    ResponseEntity<CreateReservationResponse> create(LoginUser user,
                                                     @RequestHeader(name = "Idempotency-Key", required = false)
                                                     String idempotencyKey,
                                                     @RequestBody CreateReservationRequest request) {
        Created created = service.create(user.id(), request.toCommand(checkKey(idempotencyKey)));
        CreateReservationResponse body = CreateReservationResponse.from(created);
        if (created.replayed()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/reservations/" + created.id())).body(body);
    }

    /** 멱등 키는 1~64자 (DB 칸 varchar(64)). 빈 값이나 너무 길면 400 */
    private static String checkKey(String key) {
        if (key != null && (key.isBlank() || key.length() > 64)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key는 1~64자여야 합니다");
        }
        return key;
    }

    /**
     * 내 예약 (FR-RSV-006). 대표자·참여자로 들어간 예약 모두. 끝나지 않은 것이 위(시작 빠른 순), 끝난 것은 최근 순.
     * 키패드 번호는 대표자에게만, 참여자 이름은 가려서(김*영) 준다.
     */
    @GetMapping("/api/reservations/me")
    List<MyReservationResponse> mine(LoginUser user) {
        return myReservations.findMine(user.id()).stream().map(MyReservationResponse::from).toList();
    }

    /**
     * 시작 전 취소 (FR-RSV-007). 대표자만, BOOKED이고 시작 전일 때만.
     * 200 {"id":12,"status":"CANCELED"} / 404 없거나 내 예약 아님 / 403 참여자 / 409 이미 시작·끝남·취소됨
     */
    @PostMapping("/api/reservations/{id}/cancel")
    StatusChangeResponse cancel(LoginUser user, @PathVariable long id) {
        Canceled c = cancels.cancel(id, user.id());
        return new StatusChangeResponse(c.id(), c.status().name());
    }

    /** 예약 만들기 요청 본문. 시각은 +09:00 같은 오프셋이 붙은 ISO-8601 */
    record CreateReservationRequest(Integer roomId, OffsetDateTime startAt, OffsetDateTime endAt,
                                    List<String> participantStudentIds) {

        /**
         * 서비스가 쓰는 명령으로 바꾼다. 학번 앞뒤 공백은 지운다.
         *
         * @throws BusinessException 빠진 값이 있거나 빈 학번이 있으면 400(INVALID_REQUEST)
         */
        Command toCommand(String idempotencyKey) {
            if (roomId == null || startAt == null || endAt == null || participantStudentIds == null
                    || participantStudentIds.stream().anyMatch(s -> s == null || s.isBlank())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            return new Command(roomId, startAt.toInstant(), endAt.toInstant(),
                    participantStudentIds.stream().map(String::trim).toList(), idempotencyKey);
        }
    }
}
