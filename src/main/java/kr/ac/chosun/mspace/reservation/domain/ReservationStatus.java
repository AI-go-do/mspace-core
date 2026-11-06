package kr.ac.chosun.mspace.reservation.domain;

/**
 * 예약 상태. 이름은 DB CHECK(V1 reservation.status)와 요구사항 명세 v0.4와 같다.
 * 흐름: BOOKED → IN_USE → CHECKED_OUT / AUTO_CHECKED_OUT / AWAY_RELEASED(자리 비움 15분, V11), 그 밖에 CANCELED(시작 전 취소), NO_SHOW.
 * 어떤 상태에서 어떤 상태로 갈 수 있는지는 DB 트리거(reservation_status_guard)가 마지막에 막고,
 * 각 기능은 "지금 상태가 맞을 때만" 바꾸는 조건부 UPDATE로 바꾼다 (ReservationStatusPort).
 */
public enum ReservationStatus {
    BOOKED,
    IN_USE,
    CHECKED_OUT,
    AUTO_CHECKED_OUT,
    AWAY_RELEASED,
    NO_SHOW,
    CANCELED;

    /** 끝나지 않은 예약인지. DB 제약(no_room_overlap, one_active_per_owner)의 WHERE와 같은 기준 */
    public boolean isActive() {
        return this == BOOKED || this == IN_USE;
    }
}
