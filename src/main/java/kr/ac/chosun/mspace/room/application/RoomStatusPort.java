package kr.ac.chosun.mspace.room.application;

import java.time.Instant;
import java.util.List;

/** 방 현황(FR-DEV-102)에 필요한 데이터를 DB에서 가져오는 창구(포트). 방 목록은 RoomAvailabilityPort를 같이 쓴다 */
public interface RoomStatusPort {

    /** 끝이 이 시각보다 뒤인 끝나지 않은(BOOKED, IN_USE) 예약. 지금 걸린 것과 앞으로 올 것 */
    List<ActiveReservation> findReservationsEndingAfter(Instant at);

    /** 방마다 마지막 재실 센서 상태 (room_presence). 센서 신호가 한 번도 안 온 방은 빠진다 */
    List<PresenceRow> findPresence();

    /** @param inUse 체크인해서 사용 중(IN_USE)이면 true, 입실 전(BOOKED)이면 false */
    record ActiveReservation(int roomId, Instant startAt, Instant endAt, boolean inUse) {
    }

    /** @param lastReceivedAt 서버가 마지막으로 신호를 받은 시각. 오래됐으면 끊긴 것 */
    record PresenceRow(int roomId, boolean occupied, Instant lastReceivedAt) {
    }
}
