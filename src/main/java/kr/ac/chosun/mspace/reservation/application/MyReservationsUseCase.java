package kr.ac.chosun.mspace.reservation.application;

import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;

/**
 * 내 예약 보기 입력 포트 (FR-RSV-006). 구현: {@link MyReservationsService}
 */
public interface MyReservationsUseCase {

    /**
     * 내가 대표자이거나 참여자인 예약 전부.
     * 순서: 끝나지 않은 예약(BOOKED·IN_USE)이 위, 시작 빠른 순 → 끝난 예약은 최근 것부터.
     *
     * @param userId 로그인 회원 id
     */
    List<MyReservation> findMine(long userId);

    /**
     * @param owner      내가 대표자인지
     * @param accessCode      키패드 번호. 대표자에게만 주고, 참여자면 null
     * @param checkinDeadline 입실 마감. 미리 예약은 max(시작, 예약한 시각) + 15분, 즉시 시작은 시작 + 10분
     */
    record MyReservation(long id, int roomId, String roomName, ZonedDateTime startAt, ZonedDateTime endAt,
                         ReservationStatus status, boolean owner, String accessCode, ZonedDateTime checkinDeadline,
                         List<Participant> participants) {
    }

    /** @param name 가린 이름 (김*영) */
    record Participant(String name, boolean owner) {
    }
}
