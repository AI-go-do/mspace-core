package kr.ac.chosun.mspace.reservation.adapter.in.web;

import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.reservation.application.MyReservationsUseCase.MyReservation;
import kr.ac.chosun.mspace.reservation.domain.AccessQr;

/**
 * GET /api/reservations/me 응답 한 건. 영준 비서(my_reservations)·내 예약 화면과 맞춘 모양 (11/2 합의).
 * 필드 이름을 바꾸면 비서·화면도 같이 바꿔야 한다.
 *
 * @param owner           내가 대표자인지
 * @param accessCode      키패드 번호. 대표자일 때만 값이 있고 참여자면 null
 * @param qrText          폰에 띄울 입실 QR 글자 (MSPACE:방:번호). accessCode가 null이면 null
 * @param checkinDeadline 입실 마감. 이때까지 입실하지 않으면 노쇼 (화면이 직접 계산하지 않게 서버 값을 준다)
 * @param cancelableUntil 이 시각 전까지(BOOKED·대표자) 취소할 수 있다. 미리 예약은 시작, 즉시 시작은 누른 뒤 5분(설정값)
 * @param checkedInAt     입실한 시각. 입실 안 했으면(노쇼·취소·예약됨) null
 * @param checkedOutAt    퇴실한 시각(퇴실 버튼·시간 끝나 자동·자리 비움). 아직 안 나갔으면 null
 */
record MyReservationResponse(long id, Room room, ZonedDateTime startAt, ZonedDateTime endAt, String status,
                             boolean owner, String accessCode, ZonedDateTime checkinDeadline,
                             ZonedDateTime cancelableUntil, List<Person> participants, String qrText,
                             ZonedDateTime checkedInAt, ZonedDateTime checkedOutAt) {

    record Room(int id, String name) {
    }

    /** @param name 가린 이름 (홍*동) */
    record Person(String name, boolean owner) {
    }

    static MyReservationResponse from(MyReservation r) {
        return new MyReservationResponse(r.id(), new Room(r.roomId(), r.roomName()), r.startAt(), r.endAt(),
                r.status().name(), r.owner(), r.accessCode(), r.checkinDeadline(), r.cancelableUntil(),
                r.participants().stream().map(p -> new Person(p.name(), p.owner())).toList(),
                AccessQr.text(r.roomId(), r.accessCode()), r.checkedInAt(), r.checkedOutAt());
    }
}
