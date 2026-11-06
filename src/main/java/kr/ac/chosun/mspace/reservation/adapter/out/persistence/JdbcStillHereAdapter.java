package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import kr.ac.chosun.mspace.reservation.application.StillHerePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcStillHereAdapter implements StillHerePort {

    private final JdbcClient jdbc;

    JdbcStillHereAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markIfInUse(long reservationId, long ownerId, Instant now) {
        // 자리 비움 정리(releaseAway)는 FOR UPDATE SKIP LOCKED라, 이 UPDATE가 줄을 잡고 있으면 그 분은 건너뛰고
        // 다음 분에 새 still_here_at으로 다시 본다. 정리가 먼저 풀었으면 IN_USE가 아니라 0줄 → 409
        return jdbc.sql("""
                        UPDATE reservation SET still_here_at = :now
                        WHERE id = :id AND owner_id = :owner AND status = 'IN_USE' AND still_here_at IS NULL
                        """)
                .param("id", reservationId)
                .param("owner", ownerId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }
}
