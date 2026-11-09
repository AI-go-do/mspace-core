package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.reservation.application.RelayChancePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRelayChanceAdapter implements RelayChancePort {

    private final JdbcClient jdbc;

    JdbcRelayChanceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Booked> bookedOthers(Instant dayStart, Instant dayEnd, Instant from, long userId) {
        return jdbc.sql("""
                        SELECT r.room_id, r.start_at, r.end_at, r.created_at
                        FROM reservation r
                        WHERE r.status = 'BOOKED' AND NOT r.is_instant
                          AND r.start_at >= :dayStart AND r.start_at < :dayEnd AND r.start_at > :from
                          AND NOT EXISTS (SELECT 1 FROM reservation_participant p
                                          WHERE p.reservation_id = r.id AND p.user_id = :user)
                        """)
                .param("dayStart", Timestamp.from(dayStart)).param("dayEnd", Timestamp.from(dayEnd))
                .param("from", Timestamp.from(from)).param("user", userId)
                .query((rs, n) -> new Booked(rs.getInt(1), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()))
                .list();
    }

    @Override
    public List<SlotHistory> history(Instant since, Instant until, int stopBeforeMinutes) {
        // 결과가 난 미리 예약만: 시작 시각이 지난 것. 즉시 시작은 미리 취소할 일이 없어 뺀다
        return jdbc.sql("""
                        SELECT extract(isodow FROM r.start_at AT TIME ZONE 'Asia/Seoul')::int AS dow,
                               extract(hour FROM r.start_at AT TIME ZONE 'Asia/Seoul')::int AS hr,
                               count(*) FILTER (WHERE r.status = 'CANCELED'
                                   AND r.canceled_at < r.start_at - make_interval(mins => :stop)) AS canceled,
                               count(*) AS total
                        FROM reservation r
                        WHERE NOT r.is_instant AND r.start_at >= :since AND r.start_at < :until
                        GROUP BY 1, 2
                        """)
                .param("since", Timestamp.from(since)).param("until", Timestamp.from(until))
                .param("stop", stopBeforeMinutes)
                .query((rs, n) -> new SlotHistory(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)))
                .list();
    }

    @Override
    public int waiting(int roomId, Instant start, Instant end) {
        return jdbc.sql("""
                        SELECT count(*) FROM relay_wait
                        WHERE room_id = :room AND status = 'WAITING' AND start_at >= :start AND start_at < :end
                        """)
                .param("room", roomId).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query((rs, n) -> rs.getInt(1)).single();
    }
}
