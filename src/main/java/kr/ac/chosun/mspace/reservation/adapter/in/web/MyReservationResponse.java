package kr.ac.chosun.mspace.reservation.adapter.in.web;

import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.reservation.application.MyReservationsUseCase.MyReservation;

/**
 * GET /api/reservations/me 응답 한 건. 영준 비서(my_reservations)·내 예약 화면과 맞춘 모양 (11/2 합의).
 * 필드 이름을 바꾸면 비서·화면도 같이 바꿔야 한다.
 *
 * @param owner           내가 대표자인지
 * @param accessCode      키패드 번호. 대표자일 때만 값이 있고 참여자면 null
 * @param checkinDeadline 입실 마감. 이때까지 입실하지 않으면 노쇼 (화면이 직접 계산하지 않게 서버 값을 준다)
 */
record MyReservationResponse(long id, Room room, ZonedDateTime startAt, ZonedDateTime endAt, String status,
                             boolean owner, String accessCode, ZonedDateTime checkinDeadline,
                             List<Person> participants) {

    record Room(int id, String name) {
    }

    /** @param name 가린 이름 (김*영) */
    record Person(String name, boolean owner) {
    }

    static MyReservationResponse from(MyReservation r) {
        return new MyReservationResponse(r.id(), new Room(r.roomId(), r.roomName()), r.startAt(), r.endAt(),
                r.status().name(), r.owner(), r.accessCode(), r.checkinDeadline(),
                r.participants().stream().map(p -> new Person(p.name(), p.owner())).toList());
    }
}
