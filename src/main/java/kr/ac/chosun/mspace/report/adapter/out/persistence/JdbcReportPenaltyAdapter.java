package kr.ac.chosun.mspace.report.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.report.application.ReportPenaltyPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcReportPenaltyAdapter implements ReportPenaltyPort {

    /** 예약 저장 잠금(방 = 음수, 회원 = 양수 id)과 겹치지 않는 번호대. 벌칙만 이 번호로 잠근다 */
    private static final long LOCK_BASE = 4_000_000_000_000L;

    private final JdbcClient jdbc;

    JdbcReportPenaltyAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long reporterId(long reportId) {
        return jdbc.sql("SELECT reporter_id FROM report WHERE id = :id").param("id", reportId)
                .query(Long.class).single();
    }

    @Override
    public Optional<Violator> findViolator(long reportId) {
        // 신고 시각 전에 그 방에 입실(checked_in_at)한 예약 중 가장 늦은 것. 사용 중이든 퇴실했든 같다
        return jdbc.sql("""
                        SELECT r.owner_id, r.id
                        FROM report p
                        JOIN reservation r ON r.room_id = p.room_id
                        WHERE p.id = :id
                          AND r.id <> p.reporter_reservation_id
                          AND r.checked_in_at IS NOT NULL
                          AND r.checked_in_at <= p.created_at
                        ORDER BY r.checked_in_at DESC, r.id DESC
                        LIMIT 1
                        """)
                .param("id", reportId)
                .query((rs, n) -> new Violator(rs.getLong("owner_id"), rs.getLong("id")))
                .optional();
    }

    @Override
    public void lockUser(long userId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", LOCK_BASE + userId).query((rs, n) -> 1).list();
    }

    @Override
    public void warn(long userId, WarningReason reason, long reportId, Long reservationId, Instant now) {
        jdbc.sql("""
                        INSERT INTO user_warning (user_id, reason, report_id, reservation_id, created_at)
                        VALUES (:user, :reason, :report, :reservation, :now)
                        """)
                .param("user", userId).param("reason", reason.name()).param("report", reportId)
                .param("reservation", reservationId).param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public int countWarnings(long userId, WarningReason reason, Instant now, int windowDays) {
        String lastSuspension = reason == WarningReason.VIOLATION
                ? "SELECT max(starts_at) FROM user_suspension WHERE user_id = :user AND reason = 'WARNINGS'"
                : "SELECT max(starts_at) FROM report_suspension WHERE user_id = :user";
        return jdbc.sql("""
                        SELECT count(*) FROM user_warning
                        WHERE user_id = :user AND reason = :reason
                          AND created_at > CAST(:now AS timestamptz) - make_interval(days => :days)
                          AND created_at >= coalesce((%s), '-infinity'::timestamptz)
                        """.formatted(lastSuspension))
                .param("user", userId).param("reason", reason.name())
                .param("now", Timestamp.from(now)).param("days", windowDays)
                .query(Integer.class).single();
    }

    @Override
    public boolean suspend(long userId, WarningReason reason, Instant from, Instant until) {
        // 기간이 겹치는 정지가 이미 있으면(노쇼 정지 중 등) EXCLUDE 제약에 걸리고 ON CONFLICT DO NOTHING으로 넘어간다
        String sql = reason == WarningReason.VIOLATION
                ? "INSERT INTO user_suspension (user_id, reason, starts_at, ends_at) VALUES (:user, 'WARNINGS', :from, :until) ON CONFLICT DO NOTHING"
                : "INSERT INTO report_suspension (user_id, starts_at, ends_at) VALUES (:user, :from, :until) ON CONFLICT DO NOTHING";
        return jdbc.sql(sql).param("user", userId)
                .param("from", Timestamp.from(from)).param("until", Timestamp.from(until))
                .update() == 1;
    }

    @Override
    public Optional<Instant> reportSuspendedUntil(long userId, Instant now) {
        return jdbc.sql("""
                        SELECT max(ends_at) FROM report_suspension
                        WHERE user_id = :user AND starts_at <= :now AND ends_at > :now
                        """)
                .param("user", userId).param("now", Timestamp.from(now))
                .query(Timestamp.class).optional().map(Timestamp::toInstant);
    }
}
