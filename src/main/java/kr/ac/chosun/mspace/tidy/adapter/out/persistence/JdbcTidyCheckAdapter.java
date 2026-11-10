package kr.ac.chosun.mspace.tidy.adapter.out.persistence;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.tidy.application.TidyCheckPort;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.CurrentReservation;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Item;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.LastEnded;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.LeftItem;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Listed;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Phase;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Result;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.TidyCheck;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 표는 V19__tidy_check.sql, V26__tidy_recheck.sql. 트랜잭션은 서비스가 연다. 조회는 모두 RowMapper (query(X.class)는 SonarQube S2259) */
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
    public boolean enteredSince(int roomId, long reservationId, Instant since) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM reservation
                                       WHERE room_id = :room AND id <> :id AND checked_in_at >= :since)
                        """)
                .param("room", roomId).param("id", reservationId).param("since", Timestamp.from(since))
                .query((rs, n) -> rs.getBoolean(1)).single();
    }

    @Override
    public Optional<String> lockReservation(int roomId, long reservationId) {
        return jdbc.sql("SELECT status FROM reservation WHERE id = :id AND room_id = :room FOR UPDATE")
                .param("id", reservationId).param("room", roomId)
                .query((rs, n) -> rs.getString(1)).optional();
    }

    @Override
    public Optional<Existing> findByEvent(String eventId) {
        return jdbc.sql("SELECT reservation_id, phase, report_id FROM tidy_check WHERE event_id = :id").param("id", eventId)
                .query((rs, n) -> new Existing(rs.getLong(1), Phase.valueOf(rs.getString(2)), rs.getObject(3, Long.class)))
                .optional();
    }

    @Override
    public Optional<Result> result(long reservationId, Phase phase) {
        return jdbc.sql("SELECT result FROM tidy_check WHERE reservation_id = :id AND phase = :phase")
                .param("id", reservationId).param("phase", phase.name())
                .query((rs, n) -> Result.valueOf(rs.getString(1))).optional();
    }

    @Override
    public void insert(TidyCheck c, Long reportId, Instant receivedAt) {
        try {
            insertRow(c, reportId, receivedAt);
        } catch (DuplicateKeyException e) {
            // 같은 eventId가 다른 예약으로 동시에 들어온 경우 (예약 잠금은 예약마다라 둘 다 여기까지 온다)
            throw new BusinessException(ErrorCode.TIDY_CHECK_EXISTS);
        }
    }

    private void insertRow(TidyCheck c, Long reportId, Instant receivedAt) {
        jdbc.sql("""
                        INSERT INTO tidy_check (event_id, reservation_id, room_id, device_id, phase, result, items,
                                                confidences, left_items, report_id, observed_at, received_at)
                        VALUES (:event, :reservation, :room, :device, :phase, :result, :items, :confidences, '{}',
                                :report, :observed, :received)
                        """)
                .param("event", c.eventId()).param("reservation", c.reservationId()).param("room", c.roomId())
                .param("device", c.deviceId()).param("phase", c.phase().name()).param("result", c.result().name())
                .param("items", c.items().stream().map(Item::name).toArray(String[]::new))
                .param("confidences", c.items().stream().map(Item::confidence).toArray(Float[]::new))
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

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> rs.getBoolean(1)).optional().orElse(false);
    }

    @Override
    public List<Listed> receivedSince(Instant since) {
        return jdbc.sql("""
                        SELECT t.reservation_id, t.room_id, ro.name, r.checked_out_at, t.phase, t.result, t.items,
                               t.confidences, t.left_items, t.report_id, t.observed_at
                        FROM tidy_check t
                        JOIN reservation r ON r.id = t.reservation_id
                        JOIN rooms ro ON ro.id = t.room_id
                        WHERE t.received_at >= :since
                        ORDER BY t.received_at DESC, t.id DESC
                        """)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> {
                    Timestamp ended = rs.getTimestamp(4);
                    return new Listed(rs.getLong(1), rs.getInt(2), rs.getString(3),
                            ended == null ? null : ended.toInstant(), Phase.valueOf(rs.getString(5)),
                            Result.valueOf(rs.getString(6)), items(rs.getArray(7), rs.getArray(8)),
                            Arrays.stream((String[]) rs.getArray(9).getArray()).map(LeftItem::valueOf).toList(),
                            rs.getObject(10, Long.class), rs.getTimestamp(11).toInstant());
                })
                .list();
    }

    /** 이름과 확신도를 짝지어 되돌린다. 11/10 전 기록은 확신도가 없어(NULL) 모두 null */
    static List<Item> items(Array names, Array confidences) throws SQLException {
        String[] n = (String[]) names.getArray();
        Float[] c = confidences == null ? new Float[n.length] : (Float[]) confidences.getArray();
        List<Item> out = new ArrayList<>(n.length);
        for (int i = 0; i < n.length; i++) {
            out.add(new Item(n[i], c[i]));
        }
        return out;
    }
}
