package kr.ac.chosun.mspace.room.adapter.out.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kr.ac.chosun.mspace.room.application.RoomWatchPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 빈방 알림 저장 (V25) */
@Component
class JdbcRoomWatchAdapter implements RoomWatchPort {

    /** 지금 시각을 덮는 끝나지 않은 예약. 화면 빈방 표시(occupiedNow)와 같은 반열린 구간 [시작, 끝) */
    private static final String OCCUPIED = """
            EXISTS (SELECT 1 FROM reservation r
                    WHERE r.room_id = %s AND r.status IN ('BOOKED', 'IN_USE')
                      AND r.start_at <= :now AND r.end_at > :now)""";

    private final JdbcClient jdbc;

    JdbcRoomWatchAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean occupiedAt(int roomId, Instant at) {
        return jdbc.sql("SELECT " + OCCUPIED.formatted(":room"))
                .param("room", roomId).param("now", Timestamp.from(at))
                .query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public WatchRow watch(long userId, int roomId, LocalDate date, Instant now) {
        // 이미 켜져 있으면 유일 인덱스에 걸려 아무것도 안 넣는다. 그다음 켜진 줄을 읽는다
        jdbc.sql("""
                        INSERT INTO room_watch (user_id, room_id, watch_date, created_at)
                        VALUES (:user, :room, :date, :now)
                        ON CONFLICT (user_id, room_id, watch_date) WHERE notified_at IS NULL AND canceled_at IS NULL
                        DO NOTHING
                        """)
                .param("user", userId).param("room", roomId).param("date", Date.valueOf(date))
                .param("now", Timestamp.from(now)).update();
        return myWatches(userId, date).stream().filter(w -> w.roomId() == roomId).findFirst().orElseThrow();
    }

    @Override
    public List<WatchRow> myWatches(long userId, LocalDate date) {
        return jdbc.sql("""
                        SELECT room_id, created_at FROM room_watch
                        WHERE user_id = :user AND watch_date = :date AND notified_at IS NULL AND canceled_at IS NULL
                        ORDER BY room_id
                        """)
                .param("user", userId).param("date", Date.valueOf(date))
                .query((rs, n) -> new WatchRow(rs.getInt(1), rs.getTimestamp(2).toInstant())).list();
    }

    @Override
    public void unwatch(long userId, int roomId, LocalDate date, Instant now) {
        jdbc.sql("""
                        UPDATE room_watch SET canceled_at = :now
                        WHERE user_id = :user AND room_id = :room AND watch_date = :date
                          AND notified_at IS NULL AND canceled_at IS NULL
                        """)
                .param("user", userId).param("room", roomId).param("date", Date.valueOf(date))
                .param("now", Timestamp.from(now)).update();
    }

    @Override
    public List<Freed> markFreed(LocalDate date, Instant now) {
        // 조건부 UPDATE 한 문장이라 정리가 겹쳐 돌아도 한 번만 알린다
        return jdbc.sql("""
                        UPDATE room_watch w SET notified_at = :now
                        FROM rooms m
                        WHERE m.id = w.room_id AND w.watch_date = :date
                          AND w.notified_at IS NULL AND w.canceled_at IS NULL
                          AND NOT %s
                        RETURNING w.user_id, w.room_id, m.name
                        """.formatted(OCCUPIED.formatted("w.room_id")))
                .param("date", Date.valueOf(date)).param("now", Timestamp.from(now))
                .query((rs, n) -> new Freed(rs.getLong(1), rs.getInt(2), rs.getString(3))).list();
    }
}
