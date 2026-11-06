package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;

/**
 * 예약 상태를 바꾸는 DB 일 (취소, 체크인, 퇴실). 구현은 adapter/out/persistence.
 * 모두 "지금 상태가 맞을 때만" 바꾸는 조건부 UPDATE라, 두 번 눌러도 한 번만 바뀐다.
 */
public interface ReservationStatusPort {

    /**
     * 이 회원이 대표자이고, BOOKED이고, 지금이 시작 전일 때만 CANCELED로 바꾼다.
     * 즉시 시작 예약은 누른 뒤 instantCancelMinutes 안이고 입실 마감(checkin_deadline) 전이면 시작 후라도 바꾼다.
     *
     * @return 바꿨으면 true. 조건이 하나라도 안 맞으면 아무것도 바꾸지 않고 false
     */
    boolean cancelIfBookedBeforeStart(long reservationId, long ownerId, int instantCancelMinutes);

    /**
     * BOOKED이고 지금이 번호 유효 시간(시작 openBefore 전 ~ 끝 전) 안이고 입실 마감(checkin_deadline) 전일 때만
     * IN_USE로 바꾼다 (체크인).
     * 시각은 앱 Clock으로 받는다: V1 트리거는 BOOKED → IN_USE에 시각 조건이 없어서 DB 시계와 어긋날 일이 없다.
     *
     * @return 바꿨으면 true
     */
    boolean checkInIfBooked(long reservationId, Instant now, int openBeforeMinutes);

    /**
     * 이 회원이 대표자이고 IN_USE일 때만 CHECKED_OUT으로 바꾼다 (퇴실). 방·참여자 시간이 바로 풀린다.
     * 퇴실 시각은 앱 Clock으로 받는다: 연장은 이 시각을 새 예약 시작으로 쓰므로 DB now()와 어긋나면 안 된다.
     *
     * @return 바꿨으면 true
     */
    boolean checkoutIfInUse(long reservationId, long ownerId, Instant now);
}
