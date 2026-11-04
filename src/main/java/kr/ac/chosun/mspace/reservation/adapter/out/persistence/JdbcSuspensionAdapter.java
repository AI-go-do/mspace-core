package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import kr.ac.chosun.mspace.reservation.application.SuspensionPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 이용 정지 조회 (V9 user_suspension). 정지 중 = 시작 ≤ 지금 < 끝 */
@Component
class JdbcSuspensionAdapter implements SuspensionPort {

    private final JdbcClient jdbc;

    JdbcSuspensionAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> suspendedUntil(long userId, Instant now) {
        return jdbc.sql("""
                        SELECT max(ends_at) FROM user_suspension
                        WHERE user_id = :id AND starts_at <= :now AND ends_at > :now
                        """)
                .param("id", userId).param("now", Timestamp.from(now))
                .query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    @Override
    public boolean anySuspended(Collection<Long> userIds, Instant now) {
        if (userIds.isEmpty()) {
            return false;
        }
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_suspension
                                       WHERE user_id IN (:ids) AND starts_at <= :now AND ends_at > :now)
                        """)
                .param("ids", userIds).param("now", Timestamp.from(now))
                .query(Boolean.class).single();
    }
}
