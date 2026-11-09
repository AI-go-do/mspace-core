package kr.ac.chosun.mspace.room.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface RoomWatchPort {

    /** 지금 시각을 덮는 끝나지 않은 예약이 있는지 */
    boolean occupiedAt(int roomId, Instant at);

    /** 켜고 켜진 줄을 돌려준다. 이미 켜져 있으면 그 줄 */
    WatchRow watch(long userId, int roomId, LocalDate date, Instant now);

    List<WatchRow> myWatches(long userId, LocalDate date);

    void unwatch(long userId, int roomId, LocalDate date, Instant now);

    /** 오늘 켜져 있고 지금 빈 방의 지켜보기를 알림 처리하고 돌려준다 (한 문장, 동시에 돌아도 한 번만) */
    List<Freed> markFreed(LocalDate date, Instant now);

    record WatchRow(int roomId, Instant createdAt) {
    }

    record Freed(long userId, int roomId, String roomName) {
    }
}
