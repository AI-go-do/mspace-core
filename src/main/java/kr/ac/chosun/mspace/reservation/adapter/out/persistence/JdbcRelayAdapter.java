package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.reservation.application.RelayPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 빈방 릴레이 저장 (V24) */
@Component
class JdbcRelayAdapter implements RelayPort {

    private static final String OFFER_COLUMNS =
            "id, user_id, room_id, start_at, end_at, expires_at, status, source_reservation_id";

    /** 같은 칸 줄에서 이 기다리기 앞에 선 사람 수 + 1 */
    private static final String WAIT_ORDER = """
            (SELECT count(*) FROM relay_wait q
             WHERE q.room_id = w.room_id AND q.start_at = w.start_at AND q.status = 'WAITING'
               AND q.id <= w.id)""";

    private final JdbcClient jdbc;

    JdbcRelayAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockRoom(int roomId) {
        lock(-(long) roomId);
    }

    @Override
    public void lockAdvance() {
        lock(Long.MIN_VALUE);  // 방(-방 id)·회원(id)·신고 정지(4조+id) 번호와 겹치지 않음
    }

    @Override
    public void lockUser(long userId) {
        lock(userId);
    }

    private void lock(long key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", key).query((rs, n) -> 1).list();
    }

    @Override
    public SlotState slotState(int roomId, Instant start, Instant end, long userId) {
        // 끝나지 않은 예약이나, 다른 사람에게 넘겨 둔 열린 자리가 있으면 "차 있음"
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM reservation r
                                       WHERE r.room_id = :room AND r.status IN ('BOOKED', 'IN_USE')
                                         AND tstzrange(r.start_at, r.end_at) && tstzrange(:start, :end))
                               OR EXISTS (SELECT 1 FROM relay_offer o
                                          WHERE o.room_id = :room AND o.status = 'OPEN' AND o.user_id <> :user
                                            AND tstzrange(o.start_at, o.end_at) && tstzrange(:start, :end)) AS taken,
                               EXISTS (SELECT 1 FROM reservation r JOIN reservation_participant p ON p.reservation_id = r.id
                                       WHERE r.room_id = :room AND r.status IN ('BOOKED', 'IN_USE') AND p.user_id = :user
                                         AND tstzrange(r.start_at, r.end_at) && tstzrange(:start, :end)) AS mine
                        """)
                .param("room", roomId).param("user", userId)
                .param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query((rs, n) -> new SlotState(rs.getBoolean("taken"), rs.getBoolean("mine"))).single();
    }

    @Override
    public int countActiveWaits(long userId, Instant now) {
        return jdbc.sql("""
                        SELECT count(*) FROM relay_wait
                        WHERE user_id = :user AND status IN ('WAITING', 'OFFERED') AND start_at > :now
                        """)
                .param("user", userId).param("now", Timestamp.from(now))
                .query((rs, n) -> rs.getInt(1)).single();
    }

    @Override
    public Optional<WaitRow> findActiveWait(long userId, int roomId, Instant startAt) {
        return jdbc.sql("""
                        SELECT w.id, w.room_id, w.start_at, %s AS place FROM relay_wait w
                        WHERE w.user_id = :user AND w.room_id = :room AND w.start_at = :start AND w.status = 'WAITING'
                        """.formatted(WAIT_ORDER))
                .param("user", userId).param("room", roomId).param("start", Timestamp.from(startAt))
                .query(JdbcRelayAdapter::waitRow).optional();
    }

    @Override
    public WaitRow insertWait(long userId, int roomId, Instant startAt, Instant now) {
        long id = jdbc.sql("""
                        INSERT INTO relay_wait (user_id, room_id, start_at, created_at)
                        VALUES (:user, :room, :start, :now) RETURNING id
                        """)
                .param("user", userId).param("room", roomId).param("start", Timestamp.from(startAt))
                .param("now", Timestamp.from(now))
                .query((rs, n) -> rs.getLong(1)).single();
        return jdbc.sql("SELECT w.id, w.room_id, w.start_at, %s AS place FROM relay_wait w WHERE w.id = :id"
                        .formatted(WAIT_ORDER))
                .param("id", id).query(JdbcRelayAdapter::waitRow).single();
    }

    @Override
    public boolean cancelWait(long userId, long waitId, Instant now) {
        return jdbc.sql("""
                        UPDATE relay_wait SET status = 'CANCELED', ended_at = :now
                        WHERE id = :id AND user_id = :user AND status = 'WAITING'
                        """)
                .param("id", waitId).param("user", userId).param("now", Timestamp.from(now)).update() == 1;
    }

    @Override
    public List<WaitRow> myWaits(long userId, Instant now) {
        return jdbc.sql("""
                        SELECT w.id, w.room_id, w.start_at, %s AS place FROM relay_wait w
                        WHERE w.user_id = :user AND w.status = 'WAITING' AND w.start_at > :now
                        ORDER BY w.start_at, w.room_id
                        """.formatted(WAIT_ORDER))
                .param("user", userId).param("now", Timestamp.from(now))
                .query(JdbcRelayAdapter::waitRow).list();
    }

    @Override
    public List<OfferRow> myOpenOffers(long userId, Instant now) {
        return jdbc.sql("""
                        SELECT %s FROM relay_offer WHERE user_id = :user AND status = 'OPEN' AND expires_at > :now
                        ORDER BY expires_at
                        """.formatted(OFFER_COLUMNS))
                .param("user", userId).param("now", Timestamp.from(now))
                .query(JdbcRelayAdapter::offerRow).list();
    }

    @Override
    public Optional<OfferRow> findOffer(long offerId) {
        return jdbc.sql("SELECT %s FROM relay_offer WHERE id = :id".formatted(OFFER_COLUMNS))
                .param("id", offerId).query(JdbcRelayAdapter::offerRow).optional();
    }

    @Override
    public boolean declineOffer(long userId, long offerId, Instant now) {
        return endOffers("o.id = :id AND o.user_id = :user AND o.status = 'OPEN'", "DECLINED", now, offerId, userId, 0) == 1;
    }

    @Override
    public int expireOffers(int roomId, Instant now) {
        return endOffers("o.room_id = :room AND o.status = 'OPEN' AND o.expires_at <= :now", "EXPIRED", now, 0, 0,
                roomId);
    }

    @Override
    public List<Integer> roomsToAdvance(Instant now, Instant from) {
        // advance가 손댈 방: 5분 지난 열린 넘김, 다음 사람을 아직 안 찾은 넘김, 첫 넘기기가 실패한 취소
        return jdbc.sql("""
                        SELECT room_id FROM relay_offer WHERE status = 'OPEN' AND expires_at <= :now
                        UNION
                        SELECT room_id FROM relay_offer WHERE status IN ('DECLINED', 'EXPIRED', 'TAKEN') AND NOT passed_on
                        UNION
                        SELECT r.room_id FROM reservation r
                        WHERE r.status = 'CANCELED' AND r.start_at > :from
                          AND EXISTS (SELECT 1 FROM relay_wait w
                                      WHERE w.room_id = r.room_id AND w.status = 'WAITING'
                                        AND w.start_at >= r.start_at AND w.start_at < r.end_at)
                          AND NOT EXISTS (SELECT 1 FROM relay_offer o WHERE o.source_reservation_id = r.id)
                        ORDER BY 1
                        """)
                .param("now", Timestamp.from(now)).param("from", Timestamp.from(from)).query((rs, n) -> rs.getInt(1)).list();
    }

    @Override
    public List<Integer> roomsWithExpiredOffers(Instant now) {
        return jdbc.sql("SELECT DISTINCT room_id FROM relay_offer WHERE status = 'OPEN' AND expires_at <= :now ORDER BY room_id")
                .param("now", Timestamp.from(now)).query((rs, n) -> rs.getInt(1)).list();
    }

    @Override
    public List<Long> unofferedCancellations(Instant from) {
        // 취소 뒤 첫 넘기기(released)가 실패해 남은 것: 줄 선 사람이 있는데 그 예약에서 나온 넘김이 하나도 없는 취소
        return jdbc.sql("""
                        SELECT r.id FROM reservation r
                        WHERE r.status = 'CANCELED' AND r.start_at > :from
                          AND EXISTS (SELECT 1 FROM relay_wait w
                                      WHERE w.room_id = r.room_id AND w.status = 'WAITING'
                                        AND w.start_at >= r.start_at AND w.start_at < r.end_at)
                          AND NOT EXISTS (SELECT 1 FROM relay_offer o WHERE o.source_reservation_id = r.id)
                        ORDER BY r.id
                        """)
                .param("from", Timestamp.from(from)).query((rs, n) -> rs.getLong(1)).list();
    }


    /**
     * 넘김을 끝내고(넘김·시간 지남), 그 사람의 기다리기도 끝낸다: 넘겨받은 기다리기와, 같은 구간 안 칸에 걸어 둔 다른 기다리기
     * (안 끝내면 같은 자리가 그 사람에게 다시 온다)
     */
    private int endOffers(String where, String status, Instant now, long offerId, long userId, int roomId) {
        return jdbc.sql("""
                        WITH o AS (
                            UPDATE relay_offer o SET status = :status
                            WHERE %s
                            RETURNING o.wait_id, o.user_id, o.room_id, o.start_at, o.end_at
                        ), w AS (
                            UPDATE relay_wait w SET status = 'DECLINED', ended_at = :now
                            FROM o
                            WHERE w.user_id = o.user_id AND w.room_id = o.room_id
                              AND (w.id = o.wait_id
                                   OR (w.status = 'WAITING' AND w.start_at >= o.start_at AND w.start_at < o.end_at))
                            RETURNING 1
                        )
                        SELECT count(*) FROM o
                        """.formatted(where))
                .param("status", status).param("now", Timestamp.from(now)).param("id", offerId).param("user", userId)
                .param("room", roomId)
                .query((rs, n) -> rs.getInt(1)).single();
    }

    @Override
    public List<OfferRow> offersToPass(Collection<Integer> roomIds) {
        if (roomIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT %s FROM relay_offer
                        WHERE status IN ('DECLINED', 'EXPIRED', 'TAKEN') AND NOT passed_on AND room_id IN (:rooms)
                        ORDER BY id FOR UPDATE SKIP LOCKED
                        """.formatted(OFFER_COLUMNS))
                .param("rooms", roomIds).query(JdbcRelayAdapter::offerRow).list();
    }

    @Override
    public void markPassed(long offerId) {
        jdbc.sql("UPDATE relay_offer SET passed_on = true WHERE id = :id").param("id", offerId).update();
    }

    @Override
    public List<Span> busy(int roomId, Instant start, Instant end) {
        return jdbc.sql("""
                        SELECT start_at, end_at FROM reservation
                        WHERE room_id = :room AND status IN ('BOOKED', 'IN_USE')
                          AND tstzrange(start_at, end_at) && tstzrange(:start, :end)
                        ORDER BY start_at
                        """)
                .param("room", roomId).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query((rs, n) -> new Span(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant())).list();
    }

    @Override
    public Optional<Released> canceledReservation(long reservationId) {
        return jdbc.sql("SELECT room_id, start_at, end_at FROM reservation WHERE id = :id AND status = 'CANCELED'")
                .param("id", reservationId)
                .query((rs, n) -> new Released(rs.getInt(1), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3).toInstant()))
                .optional();
    }

    @Override
    public Optional<OfferRow> offerToNext(int roomId, Instant start, Instant end, long sourceReservationId,
                                          Instant now, Instant expiresAt) {
        // 구간 안 칸에 줄 선(WAITING) 사람 중 가장 먼저 선 사람. 같은 구간에 이미 열린 넘김이 있으면 만들지 않는다
        return jdbc.sql("""
                        WITH next AS (
                            SELECT w.id, w.user_id FROM relay_wait w
                            WHERE w.room_id = :room AND w.status = 'WAITING' AND w.start_at >= :start AND w.start_at < :end
                              -- 취소 커밋과 방 잠금 사이, 또는 5분 지난 넘김이 아직 정리 전일 때 다른 사람이 먼저 잡았으면 넘기지 않는다
                              AND NOT EXISTS (SELECT 1 FROM reservation r
                                              WHERE r.room_id = :room AND r.status IN ('BOOKED', 'IN_USE')
                                                AND tstzrange(r.start_at, r.end_at) && tstzrange(:start, :end))
                              AND NOT EXISTS (SELECT 1 FROM relay_offer o
                                              WHERE o.room_id = :room AND o.status = 'OPEN'
                                                AND tstzrange(o.start_at, o.end_at) && tstzrange(:start, :end))
                            ORDER BY w.id  -- 줄 순서는 번호: 방 잠금 안에서 넣으니 번호가 곧 줄 선 순서 (서버 시계와 무관)
                            LIMIT 1 FOR UPDATE SKIP LOCKED
                        ), w AS (
                            UPDATE relay_wait SET status = 'OFFERED' WHERE id IN (SELECT id FROM next)
                            RETURNING id, user_id
                        )
                        INSERT INTO relay_offer (wait_id, user_id, room_id, start_at, end_at, source_reservation_id,
                                                 created_at, expires_at)
                        SELECT id, user_id, :room, :start, :end, :source, :now, :expires FROM w
                        RETURNING %s
                        """.formatted(OFFER_COLUMNS))
                .param("room", roomId).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .param("source", sourceReservationId).param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(expiresAt))
                .query(JdbcRelayAdapter::offerRow).optional();
    }

    @Override
    public boolean take(long offerId, long userId, long reservationId, Instant now) {
        // 받은 넘김 TAKEN, 그 사람의 다른 기다리기 CANCELED, 다른 열린 넘김 DECLINED(다음 사람 찾기는 advance가)
        return jdbc.sql("""
                        WITH o AS (
                            UPDATE relay_offer SET status = 'TAKEN', reservation_id = :res
                            WHERE id = :id AND user_id = :user AND status = 'OPEN' AND expires_at > :now
                            RETURNING wait_id
                        ), mine AS (
                            UPDATE relay_wait SET status = 'TAKEN', ended_at = :now
                            WHERE id IN (SELECT wait_id FROM o)
                            RETURNING 1
                        ), others AS (
                            UPDATE relay_offer SET status = 'DECLINED'
                            WHERE user_id = :user AND status = 'OPEN' AND id <> :id AND EXISTS (SELECT 1 FROM o)
                            RETURNING wait_id
                        ), waits AS (
                            UPDATE relay_wait SET status = CASE WHEN status = 'OFFERED' THEN 'DECLINED' ELSE 'CANCELED' END,
                                                  ended_at = :now
                            WHERE user_id = :user AND status IN ('WAITING', 'OFFERED')
                              AND id NOT IN (SELECT wait_id FROM o) AND EXISTS (SELECT 1 FROM o)
                            RETURNING 1
                        )
                        SELECT count(*) FROM o
                        """)
                .param("id", offerId).param("user", userId).param("res", reservationId).param("now", Timestamp.from(now))
                .query((rs, n) -> rs.getInt(1)).single() == 1;
    }

    @Override
    public String roomName(int roomId) {
        return jdbc.sql("SELECT name FROM rooms WHERE id = :id").param("id", roomId)
                .query((rs, n) -> rs.getString(1)).single();
    }

    private static WaitRow waitRow(ResultSet rs, int n) throws SQLException {
        return new WaitRow(rs.getLong("id"), rs.getInt("room_id"), rs.getTimestamp("start_at").toInstant(),
                rs.getInt("place"));
    }

    private static OfferRow offerRow(ResultSet rs, int n) throws SQLException {
        return new OfferRow(rs.getLong("id"), rs.getLong("user_id"), rs.getInt("room_id"),
                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("status"),
                rs.getLong("source_reservation_id"), 1);
    }
}
