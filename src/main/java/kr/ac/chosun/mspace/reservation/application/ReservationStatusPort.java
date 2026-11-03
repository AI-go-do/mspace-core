package kr.ac.chosun.mspace.reservation.application;

/**
 * 예약 상태를 바꾸는 DB 일 (취소, 나중에 체크인·퇴실). 구현은 adapter/out/persistence.
 * 모두 "지금 상태가 맞을 때만" 바꾸는 조건부 UPDATE라, 두 번 눌러도 한 번만 바뀐다.
 * 시각 조건은 DB의 now()로 본다 (V1 상태 변경 트리거 reservation_status_guard와 같은 시계).
 */
public interface ReservationStatusPort {

    /**
     * 이 회원이 대표자이고, BOOKED이고, 지금이 시작 전일 때만 CANCELED로 바꾼다.
     *
     * @return 바꿨으면 true. 조건이 하나라도 안 맞으면 아무것도 바꾸지 않고 false
     */
    boolean cancelIfBookedBeforeStart(long reservationId, long ownerId);
}
