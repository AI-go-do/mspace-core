package kr.ac.chosun.mspace.reservation.domain;

/**
 * 예약 상태. 이름은 DB CHECK(V1 reservation.status)와 요구사항 명세 v0.4와 같다.
 * 흐름: BOOKED → IN_USE → CHECKED_OUT / AUTO_CHECKED_OUT, 그 밖에 CANCELED(시작 전 취소), NO_SHOW.
 * 어떤 상태에서 어떤 상태로 갈 수 있는지는 취소(#6)·퇴실에서 이 enum에 더한다.
 */
public enum ReservationStatus {
    BOOKED,
    IN_USE,
    CHECKED_OUT,
    AUTO_CHECKED_OUT,
    NO_SHOW,
    CANCELED;

    /** 끝나지 않은 예약인지. DB 제약(no_room_overlap, one_active_per_owner)의 WHERE와 같은 기준 */
    public boolean isActive() {
        return this == BOOKED || this == IN_USE;
    }
}
