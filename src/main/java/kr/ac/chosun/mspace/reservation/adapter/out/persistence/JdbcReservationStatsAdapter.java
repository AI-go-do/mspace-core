package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import kr.ac.chosun.mspace.reservation.application.ReservationStatsPort;
import kr.ac.chosun.mspace.reservation.domain.CreatedVia;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 예약 건수. created_at 인덱스는 따로 두지 않았다: 방 5개 × 하루 최대 약 48칸이라 한 달에 많아야 수천 줄이고,
 * 조회할 때만 도는 쿼리라 전체 훑기(seq scan)로도 수 ms 안이다. 몇 십만 줄이 되면 (created_at) 인덱스를 새 마이그레이션으로 넣는다.
 */
@Component
class JdbcReservationStatsAdapter implements ReservationStatsPort {

    private final JdbcClient jdbc;

    JdbcReservationStatsAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<CreatedVia, Long> countByCreatedVia(Instant from, Instant to) {
        Map<CreatedVia, Long> counts = new EnumMap<>(CreatedVia.class);
        jdbc.sql("""
                        SELECT created_via, count(*) AS n FROM reservation
                        WHERE (CAST(:from AS timestamptz) IS NULL OR created_at >= :from)
                          AND (CAST(:to AS timestamptz) IS NULL OR created_at < :to)
                        GROUP BY created_via
                        """)
                .param("from", utc(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("to", utc(to), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(rs -> {
                    String via = rs.getString("created_via");
                    // DEVICE는 아직 만드는 곳이 없어 세지 않는다
                    if (!"DEVICE".equals(via)) {
                        counts.put(CreatedVia.valueOf(via), rs.getLong("n"));
                    }
                });
        return counts;
    }

    /** timestamptz로 그대로 넘기려고 오프셋이 붙은 값으로 바꾼다 (null이면 null: 그쪽 끝 없음) */
    private static OffsetDateTime utc(Instant t) {
        return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    @Override
    public boolean isAdmin(long userId) {
        return jdbc.sql("SELECT role = 'ADMIN' FROM users WHERE id = :id").param("id", userId)
                .query(Boolean.class).optional().orElse(false);
    }
}
