package kr.ac.chosun.mspace.tidy.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.tidy.application.TidyCheckPort;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.CurrentReservation;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.LastEnded;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.TidyCheck;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 표는 V19__tidy_check.sql. 트랜잭션은 서비스가 연다. 조회는 모두 RowMapper (query(X.class)는 SonarQube S2259) */
@Component
class JdbcTidyCheckAdapter implements TidyCheckPort {

    private final JdbcClient jdbc;

    JdbcTidyCheckAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean roomExists(int roomId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM rooms WHERE id = :room)").param("room", roomId)
                .query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public Optional<CurrentReservation> current(int roomId, Instant now) {
        // 사용 중이거나, 시간이 됐는데 아직 입실 전인 예약. 방에는 겹치는 예약이 없어(no_room_overlap) 보통 한 줄
        return jdbc.sql("""
                        SELECT id, status, start_at, end_at FROM reservation
                        WHERE room_id = :room
                          AND (status = 'IN_USE' OR (status = 'BOOKED' AND start_at <= :now AND end_at > :now))
                        ORDER BY start_at DESC
                        LIMIT 1
                        """)
                .param("room", roomId).param("now", Timestamp.from(now))
                .query((rs, n) -> new CurrentReservation(rs.getLong("id"), rs.getString("status"),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant()))
                .optional();
    }

    @Override
    public Optional<LastEnded> lastEnded(int roomId) {
        return jdbc.sql("""
                        SELECT id, status, checked_out_at FROM reservation
                        WHERE room_id = :room
                          AND status IN ('CHECKED_OUT', 'AUTO_CHECKED_OUT', 'AWAY_RELEASED')
                          AND checked_out_at IS NOT NULL
                        ORDER BY checked_out_at DESC, id DESC
                        LIMIT 1
                        """)
                .param("room", roomId)
                .query((rs, n) -> new LastEnded(rs.getLong("id"), rs.getString("status"),
                        rs.getTimestamp("checked_out_at").toInstant()))
                .optional();
    }

    @Override
    public Optional<String> lockReservation(int roomId, long reservationId) {
        return jdbc.sql("SELECT status FROM reservation WHERE id = :id AND room_id = :room FOR UPDATE")
                .param("id", reservationId).param("room", roomId)
                .query((rs, n) -> rs.getString(1)).optional();
    }

    @Override
    public Optional<Existing> findByEvent(String eventId) {
        return jdbc.sql("SELECT report_id FROM tidy_check WHERE event_id = :id").param("id", eventId)
                .query((rs, n) -> new Existing(rs.getObject(1, Long.class))).optional();
    }

    @Override
    public boolean existsForReservation(long reservationId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM tidy_check WHERE reservation_id = :id)")
                .param("id", reservationId).query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public void insert(TidyCheck c, Long reportId, Instant receivedAt) {
        jdbc.sql("""
                        INSERT INTO tidy_check (event_id, reservation_id, room_id, device_id, result, items, left_items,
                                                report_id, observed_at, received_at)
                        VALUES (:event, :reservation, :room, :device, :result, :items, :left, :report, :observed, :received)
                        """)
                .param("event", c.eventId()).param("reservation", c.reservationId()).param("room", c.roomId())
                .param("device", c.deviceId()).param("result", c.result().name())
                .param("items", c.items().toArray(String[]::new))
                .param("left", c.leftItems().stream().map(Enum::name).distinct().toArray(String[]::new))
                .param("report", reportId)
                .param("observed", Timestamp.from(c.observedAt())).param("received", Timestamp.from(receivedAt))
                .update();
    }

    @Override
    public List<Long> members(long reservationId) {
        return jdbc.sql("""
                        SELECT user_id FROM reservation_participant WHERE reservation_id = :id
                        ORDER BY is_owner DESC, user_id
                        """)
                .param("id", reservationId).query((rs, n) -> rs.getLong(1)).list();
    }

    @Override
    public String roomName(int roomId) {
        return jdbc.sql("SELECT name FROM rooms WHERE id = :room").param("room", roomId)
                .query((rs, n) -> rs.getString(1)).single();
    }
}
