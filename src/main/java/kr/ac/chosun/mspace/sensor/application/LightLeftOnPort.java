package kr.ac.chosun.mspace.sensor.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LightLeftOnPort {

    /**
     * 지금 빈 방: 재실 신호가 "없음"이고, 지금 시각을 낀 예약(BOOKED·IN_USE)이 없는 방.
     * 재실 신호가 한 번도 안 온 방은 넣지 않는다
     */
    Set<Integer> idleRooms(Instant now);

    /** 그 방에서 가장 늦게 퇴실한 예약. 없으면 빈 값 */
    Optional<LastTeam> lastTeam(int roomId);

    /**
     * @param notified 이 예약으로 불 켜짐 알림을 이미 보냈는지 (서버를 다시 켜도 두 번 안 보내게)
     * @param userIds  대표자와 참여자
     */
    record LastTeam(long reservationId, String roomName, Instant checkedOutAt, boolean notified, List<Long> userIds) {
    }
}
