package kr.ac.chosun.mspace.reservation.adapter.in.web;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.CancelReservationUseCase;
import kr.ac.chosun.mspace.reservation.application.CancelReservationUseCase.Canceled;
import kr.ac.chosun.mspace.reservation.application.CheckoutReservationUseCase;
import kr.ac.chosun.mspace.reservation.application.CheckoutReservationUseCase.CheckedOut;
import kr.ac.chosun.mspace.reservation.application.ContinueReservationUseCase;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Command;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Created;
import kr.ac.chosun.mspace.reservation.application.MyReservationsUseCase;
import kr.ac.chosun.mspace.reservation.domain.CreatedVia;
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
    private final CheckoutReservationUseCase checkouts;
    private final ContinueReservationUseCase continuations;

    ReservationController(CreateReservationUseCase service, MyReservationsUseCase myReservations,
                          CancelReservationUseCase cancels, CheckoutReservationUseCase checkouts,
                          ContinueReservationUseCase continuations) {
        this.continuations = continuations;
        this.service = service;
        this.myReservations = myReservations;
        this.cancels = cancels;
        this.checkouts = checkouts;
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
                                                     @RequestHeader(name = CREATED_VIA_HEADER, required = false) String client,
                                                     @RequestBody CreateReservationRequest request) {
        Created created = service.create(user.id(), request.toCommand(checkKey(idempotencyKey), via(client)));
        CreateReservationResponse body = CreateReservationResponse.from(created);
        if (created.replayed()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/reservations/" + created.id())).body(body);
    }

    /**
     * 지금 바로 쓰기 = 즉시 시작 (FR-RSV-103). 버튼 누른 시각부터, 끝은 고른 30분 칸까지. 10분 안에 키패드로 입실해야 한다.
     * 예: POST /api/reservations/instant
     * {"roomId":1,"endAt":"2026-11-03T16:30:00+09:00","participantStudentIds":["20230002","20230003"]}
     * 201 만든 예약 / 400 끝이 30분 칸 아님·최대 넘음 / 409 지금 다른 예약이 있거나 다음 예약과 겹침, 대표자 1건
     */
    @PostMapping("/api/reservations/instant")
    ResponseEntity<CreateReservationResponse> startNow(LoginUser user,
                                                       @RequestHeader(name = "Idempotency-Key", required = false)
                                                       String idempotencyKey,
                                                       @RequestHeader(name = CREATED_VIA_HEADER, required = false) String client,
                                                       @RequestBody StartNowRequest request) {
        Created created = service.startNow(user.id(), request.toCommand(checkKey(idempotencyKey), via(client)));
        CreateReservationResponse body = CreateReservationResponse.from(created);
        if (created.replayed()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/reservations/" + created.id())).body(body);
    }

    /**
     * 어디서 보낸 요청인지 알리는 헤더. 비서(mspace-assistant)는 "AGENT"를 붙이고, 웹 화면은 안 붙인다(= WEB). 이름은 DB 칸 created_via와 맞췄다(영준 #43, 11/4 지영 결정).
     * WEB/AGENT 예약 건수를 따로 세는 통계용 표시라, 권한 검사에는 쓰지 않는다 (11/3 영준 요청)
     */
    static final String CREATED_VIA_HEADER = "X-Created-Via";

    /** X-Created-Via 값을 고른다. "WEB"·"AGENT"(대소문자 무시) 말고 다른 값이면 400: 오타가 WEB으로 잘못 세지지 않게 */
    private static CreatedVia via(String client) {
        CreatedVia via = CreatedVia.fromHeader(client);
        if (via == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "X-Created-Via는 WEB 또는 AGENT여야 합니다");
        }
        return via;
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
     * 키패드 번호는 대표자에게만, 참여자 이름은 가려서(홍*동) 준다.
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

    /**
     * 퇴실 (FR-RSV-008). 대표자만, 입실한(IN_USE) 예약만. 방이 바로 빈다. 조기 퇴실 확인은 화면이 한다.
     * 200 {"id":12,"status":"CHECKED_OUT"} / 404 없거나 내 예약 아님 / 403 참여자 / 409 입실 전·이미 끝남
     */
    @PostMapping("/api/reservations/{id}/checkout")
    StatusChangeResponse checkout(LoginUser user, @PathVariable long id) {
        CheckedOut c = checkouts.checkout(id, user.id());
        return new StatusChangeResponse(c.id(), c.status().name());
    }

    /**
     * 연장하기 (FR-RSV-105). 대표자, 사용 중(IN_USE), 종료 10분 전–종료 전에만. 지금 예약을 퇴실하고 지금부터 endAt까지
     * 새 예약을 바로 사용 중으로 만든다(키패드·노쇼 없음). 새 입실 번호를 주고 예전 번호는 바로 무효다.
     * 예: POST /api/reservations/12/continue {"endAt":"2026-11-03T16:00:00+09:00"}  (지금 끝 15:00 → 30분 단위, 최대 2시간)
     * 201 새 예약 / 200 같은 Idempotency-Key 재요청 / 400 끝 시각이 틀림 / 403 참여자 / 404 없거나 내 예약 아님
     * / 409 사용 중 아님·시간 밖·연장 1회 넘음·다음 칸이 이미 예약됨(이때 지금 예약은 그대로)
     */
    @PostMapping("/api/reservations/{id}/continue")
    ResponseEntity<CreateReservationResponse> continueUse(LoginUser user, @PathVariable long id,
                                                          @RequestHeader(name = "Idempotency-Key", required = false)
                                                          String idempotencyKey,
                                                          @RequestHeader(name = CREATED_VIA_HEADER, required = false)
                                                          String client,
                                                          @RequestBody ContinueRequest request) {
        if (request.endAt() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Created created = continuations.continueUse(id, user.id(), new ContinueReservationUseCase.Command(
                request.endAt().toInstant(), checkKey(idempotencyKey), via(client)));
        CreateReservationResponse body = CreateReservationResponse.from(created);
        if (created.replayed()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/reservations/" + created.id())).body(body);
    }

    /** 연장하기 요청 본문. 시작은 서버 시계(누른 시각) */
    record ContinueRequest(OffsetDateTime endAt) {
    }

    /** 즉시 시작 요청 본문. 시작은 서버 시계라 받지 않는다 */
    record StartNowRequest(Integer roomId, OffsetDateTime endAt, List<String> participantStudentIds) {

        Command toCommand(String idempotencyKey, CreatedVia via) {
            if (roomId == null || endAt == null || participantStudentIds == null
                    || participantStudentIds.stream().anyMatch(s -> s == null || s.isBlank())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            return new Command(roomId, null, endAt.toInstant(),
                    participantStudentIds.stream().map(String::trim).toList(), idempotencyKey, via);
        }
    }

    /** 예약 만들기 요청 본문. 시각은 +09:00 같은 오프셋이 붙은 ISO-8601 */
    record CreateReservationRequest(Integer roomId, OffsetDateTime startAt, OffsetDateTime endAt,
                                    List<String> participantStudentIds, Long relayOfferId) {

        /** 빈방 릴레이 없이 (테스트·예전 호출) */
        CreateReservationRequest(Integer roomId, OffsetDateTime startAt, OffsetDateTime endAt,
                                 List<String> participantStudentIds) {
            this(roomId, startAt, endAt, participantStudentIds, null);
        }

        /**
         * 서비스가 쓰는 명령으로 바꾼다. 학번 앞뒤 공백은 지운다.
         *
         * @throws BusinessException 빠진 값이 있거나 빈 학번이 있으면 400(INVALID_REQUEST)
         */
        Command toCommand(String idempotencyKey, CreatedVia via) {
            if (roomId == null || startAt == null || endAt == null || participantStudentIds == null
                    || participantStudentIds.stream().anyMatch(s -> s == null || s.isBlank())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            return new Command(roomId, startAt.toInstant(), endAt.toInstant(),
                    participantStudentIds.stream().map(String::trim).toList(), idempotencyKey, via, relayOfferId);
        }
    }
}
