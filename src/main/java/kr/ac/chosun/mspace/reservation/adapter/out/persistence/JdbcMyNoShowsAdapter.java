package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.reservation.application.MyNoShowsPort;
import kr.ac.chosun.mspace.reservation.application.MyNoShowsUseCase.Item;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMyNoShowsAdapter implements MyNoShowsPort {

    private final JdbcClient jdbc;

    JdbcMyNoShowsAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 기록 시각은 정지 셈과 같게: 노쇼 = least(입실 마감, 끝), 자리 비움 = 퇴실 시각. 대표자 기록만(owner_id 인덱스) */
    @Override
    public List<Item> find(long userId, Instant since, Instant now, int windowDays) {
        return jdbc.sql("""
                        SELECT h.*, h.no_show_excused_at IS NULL
                                    AND h.happened_at > CAST(:now AS timestamptz) - make_interval(days => :windowDays)
                                    AND h.happened_at >= coalesce((SELECT max(s.ends_at) FROM user_suspension s
                                                                  WHERE s.user_id = :user AND s.reason = 'NO_SHOW'),
                                                                 '-infinity'::timestamptz) AS counted
                        FROM (SELECT r.id, r.room_id, rm.name AS room_name, r.start_at, r.end_at, r.status,
                                     r.no_show_excused_at,
                                     CASE r.status WHEN 'NO_SHOW' THEN least(r.checkin_deadline, r.end_at)
                                                   ELSE r.checked_out_at END AS happened_at
                              FROM reservation r
                              JOIN rooms rm ON rm.id = r.room_id
                              WHERE r.owner_id = :user AND r.status IN ('NO_SHOW', 'AWAY_RELEASED')) h
                        WHERE h.happened_at > :since
                        ORDER BY h.happened_at DESC, h.id DESC
                        """)
                .param("user", userId).param("since", Timestamp.from(since)).param("now", Timestamp.from(now))
                .param("windowDays", windowDays)
                .query(JdbcMyNoShowsAdapter::item).list();
    }

    private static Item item(ResultSet rs, int n) throws SQLException {
        Timestamp excused = rs.getTimestamp("no_show_excused_at");
        return new Item(rs.getLong("id"), rs.getInt("room_id"), rs.getString("room_name"),
                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(), rs.getString("status"),
                rs.getTimestamp("happened_at").toInstant(), rs.getBoolean("counted"),
                excused == null ? null : excused.toInstant());
    }
}
