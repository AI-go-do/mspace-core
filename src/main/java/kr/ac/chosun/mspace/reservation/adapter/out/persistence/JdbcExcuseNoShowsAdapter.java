package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.reservation.application.ExcuseNoShowsPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 장치 오류 노쇼 무르기 (V23). device_fault_attempt는 device 기능이 채운다 */
@Component
class JdbcExcuseNoShowsAdapter implements ExcuseNoShowsPort {

    private final JdbcClient jdbc;

    JdbcExcuseNoShowsAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Excused> excuseMatched(Instant now) {
        return jdbc.sql("""
                        UPDATE reservation r SET no_show_excused_at = :now
                        FROM rooms rm
                        WHERE r.status = 'NO_SHOW' AND r.no_show_excused_at IS NULL AND rm.id = r.room_id
                          AND EXISTS (SELECT 1 FROM device_fault_attempt a WHERE a.reservation_id = r.id)
                        RETURNING r.id, r.owner_id, rm.name, r.start_at, least(r.checkin_deadline, r.end_at) AS no_show_at
                        """)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new Excused(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("no_show_at").toInstant()))
                .list();
    }

    /**
     * 이 노쇼를 센 정지 하나만 푼다. 정지 셈은 "지난 노쇼 정지가 끝난 뒤의 기록"만 세므로(suspendRepeatedNoShows),
     * 이 노쇼를 센 정지 = 노쇼 시각 뒤에 처음 시작한 노쇼 정지. 단, 노쇼가 다른 정지 기간 안에 생겼으면 어느 정지도 세지 않았다.
     * 그보다 뒤에 걸린 정지(이 노쇼가 끝난 정지 뒤 새 노쇼들로 걸린 것)는 건드리지 않는다
     */
    private static final String COUNTED_IN = """
            SELECT s.id FROM user_suspension s
            WHERE s.user_id = :owner AND s.reason = 'NO_SHOW' AND s.starts_at >= :noShowAt
              AND :noShowAt >= coalesce((SELECT max(p.ends_at) FROM user_suspension p
                                         WHERE p.user_id = :owner AND p.reason = 'NO_SHOW' AND p.starts_at < :noShowAt),
                                        '-infinity'::timestamptz)
            ORDER BY s.starts_at
            LIMIT 1
            """;

    @Override
    public boolean liftSuspension(long ownerId, Instant noShowAt, Instant now) {
        // 끝을 지금으로 당기면 정지 기간 CHECK(시작 < 끝)에 걸리는 "지금 막 시작한" 정지는 지운다
        int deleted = jdbc.sql("DELETE FROM user_suspension WHERE id IN (" + COUNTED_IN + ") AND starts_at >= :now")
                .param("owner", ownerId).param("noShowAt", Timestamp.from(noShowAt)).param("now", Timestamp.from(now))
                .update();
        int shortened = jdbc.sql("UPDATE user_suspension SET ends_at = :now WHERE id IN (" + COUNTED_IN + ")"
                        + " AND starts_at < :now AND ends_at > :now")
                .param("owner", ownerId).param("noShowAt", Timestamp.from(noShowAt)).param("now", Timestamp.from(now))
                .update();
        return deleted + shortened > 0;
    }

    @Override
    public List<Long> members(long reservationId) {
        return jdbc.sql("""
                        SELECT user_id FROM reservation_participant WHERE reservation_id = :id
                        ORDER BY is_owner DESC, user_id
                        """)
                .param("id", reservationId).query((rs, n) -> rs.getLong(1)).list();
    }

    /** SET LOCAL이라 이 트랜잭션에만. 무르기는 예약 몇 줄이라 보통 바로 끝남 */
    @Override
    public void limitLockWait() {
        jdbc.sql("SET LOCAL lock_timeout = '3s'").update();
    }
}
