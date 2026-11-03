package kr.ac.chosun.mspace.reservation.adapter.in.web;

import java.time.ZonedDateTime;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Created;

/**
 * POST /api/reservations 응답 본문 (화면·비서와 맞춘 계약). 서비스 결과(Created)가 바뀌어도
 * 이 모양은 그대로 두려고 따로 둔다. 필드 이름·순서를 바꾸면 화면·비서도 같이 바꿔야 한다.
 */
record CreateReservationResponse(long id, int roomId, ZonedDateTime startAt, ZonedDateTime endAt,
                                 String status, int headcount, String accessCode) {

    static CreateReservationResponse from(Created c) {
        return new CreateReservationResponse(c.id(), c.roomId(), c.startAt(), c.endAt(),
                c.status().name(), c.headcount(), c.accessCode());
    }
}
