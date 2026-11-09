package kr.ac.chosun.mspace.sensor.application;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * {@link RoomSignalsQuery} 메모리 구현. 값마다 관측 시각이 더 새로울 때만 바꾼다(늦게 온 옛 이벤트가 덮지 않게).
 * 방 5개 × 두 값이라 크기 걱정 없음. 서버 한 대 기준(여러 대로 늘리면 DB로 옮겨야 함)
 */
@Component
class LatestRoomSignals implements RoomSignalsQuery {

    private final Map<Integer, Signals> rooms = new ConcurrentHashMap<>();

    void observe(int roomId, Integer light, Boolean pir, Instant observedAt) {
        if (light == null && pir == null) {
            return;
        }
        rooms.merge(roomId, new Signals(light, light == null ? null : observedAt, pir, pir == null ? null : observedAt),
                LatestRoomSignals::newer);
    }

    private static Signals newer(Signals old, Signals in) {
        boolean light = in.light() != null && (old.lightAt() == null || in.lightAt().isAfter(old.lightAt()));
        boolean pir = in.pir() != null && (old.pirAt() == null || in.pirAt().isAfter(old.pirAt()));
        return new Signals(light ? in.light() : old.light(), light ? in.lightAt() : old.lightAt(),
                pir ? in.pir() : old.pir(), pir ? in.pirAt() : old.pirAt());
    }

    @Override
    public Map<Integer, Signals> latest() {
        return Map.copyOf(rooms);
    }
}
