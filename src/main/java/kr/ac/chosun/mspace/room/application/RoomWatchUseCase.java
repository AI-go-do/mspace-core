package kr.ac.chosun.mspace.room.application;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 빈방 알림 (11/9 지영). 사용 중인 방에 "비면 알려 줘요"를 켜 두면 예약이 끝나 방이 빌 때 알림함으로 한 번 알린다.
 * 켜 둔 날(서울) 하루만. "비었다"는 지금 시각을 덮는 끝나지 않은 예약이 없음 (화면 빈방 표시와 같은 기준).
 */
public interface RoomWatchUseCase {

    /** 오늘 켜 둔 방 (아직 안 알린 것) */
    List<Watch> myWatches(long userId);

    /** 켜기. 이미 켜져 있으면 그대로. 지금 비어 있으면 409 ROOM_WATCH_FREE */
    Watch watch(long userId, int roomId);

    /** 끄기. 켜져 있지 않아도 그대로 끝 */
    void unwatch(long userId, int roomId);

    /** 비게 된 방을 지켜보던 사람에게 알린다 (1분 정리). 알린 수 */
    int notifyFreed(Instant now);

    record Watch(int roomId, ZonedDateTime since) {
    }
}
