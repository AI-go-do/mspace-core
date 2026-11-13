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
    public Optional<Long> reporterId(long reportId) {
        // 카메라 신고는 reporter_id가 NULL이라 single()은 못 쓴다 (null 값이면 예외). 줄은 늘 하나
        return jdbc.sql("SELECT reporter_id FROM report WHERE id = :id AND reporter_id IS NOT NULL")
                .param("id", reportId).query((rs, n) -> rs.getLong(1)).optional();
    }

    @Override
    public Optional<Violator> findViolator(long reportId) {
        // 신고 시각 전에 그 방에 입실(checked_in_at)한 예약 중 가장 늦은 것. 사용 중이든 퇴실했든 같다.
        // 신고자 본인이 대표인 예약은 모두 뺀다: 연장하면 신고 근거는 새 예약이라 연장 전 예약이 남아 본인이 잡혔다 (#83)
        return jdbc.sql("""
                        SELECT r.owner_id, r.id
                        FROM report p
                        JOIN reservation r ON r.room_id = p.room_id
                        WHERE p.id = :id
                          AND (p.target_reservation_id IS NULL OR r.id = p.target_reservation_id)
                          AND r.id IS DISTINCT FROM p.reporter_reservation_id
                          AND r.owner_id IS DISTINCT FROM p.reporter_id
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
    public long warn(long userId, WarningReason reason, long reportId, Long reservationId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO user_warning (user_id, reason, report_id, reservation_id, created_at)
                        VALUES (:user, :reason, :report, :reservation, :now)
                        RETURNING id
                        """)
                .param("user", userId).param("reason", reason.name()).param("report", reportId)
                .param("reservation", reservationId).param("now", Timestamp.from(now))
                .query(Long.class).single();
    }

    @Override
    public int countWarnings(long userId, WarningReason reason, Instant now, int windowDays) {
        String lastSuspension = reason == WarningReason.VIOLATION
                ? "SELECT max(warning_id) FROM user_suspension WHERE user_id = :user AND reason = 'WARNINGS' AND lifted_at IS NULL"
                : "SELECT max(warning_id) FROM report_suspension WHERE user_id = :user AND lifted_at IS NULL";
        // 지난 정지를 건 경고와 그 앞은 이미 벌칙을 받았으니 다시 세지 않는다. 시각으로 자르면 경고와 정지가 같은 now로
        // 들어가 겹치고, now는 잠금 전에 읽혀 늦게 줄 선 경고가 더 이른 시각을 가질 수 있다 → 정지에 남긴 경고 id로 자른다.
        // id는 회원 잠금 아래에서 붙어서 같은 사람 안에서는 처리 순서와 같다.
        // 철회된 경고는 세지 않고, 철회로 푼 정지는 경계로 쓰지 않는다 (FR-RPT-105, V29): 남은 경고는 다시 센다
        return jdbc.sql("""
                        SELECT count(*) FROM user_warning
                        WHERE user_id = :user AND reason = :reason AND withdrawn_at IS NULL
                          AND created_at > CAST(:now AS timestamptz) - make_interval(days => :days)
                          AND id > coalesce((%s), 0)
                        """.formatted(lastSuspension))
                .param("user", userId).param("reason", reason.name())
                .param("now", Timestamp.from(now)).param("days", windowDays)
                .query(Integer.class).single();
    }

    @Override
    public boolean suspend(long userId, WarningReason reason, long warningId, Instant from, Instant until) {
        // 기간이 겹치는 정지가 이미 있으면(노쇼 정지 중 등) EXCLUDE 제약에 걸리고 ON CONFLICT DO NOTHING으로 넘어간다
        String sql = reason == WarningReason.VIOLATION
                ? "INSERT INTO user_suspension (user_id, reason, warning_id, starts_at, ends_at) VALUES (:user, 'WARNINGS', :warning, :from, :until) ON CONFLICT DO NOTHING"
                : "INSERT INTO report_suspension (user_id, warning_id, starts_at, ends_at) VALUES (:user, :warning, :from, :until) ON CONFLICT DO NOTHING";
        return jdbc.sql(sql).param("user", userId).param("warning", warningId)
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

    @Override
    public Optional<Instant> useSuspendedUntil(long userId, Instant now) {
        return jdbc.sql("""
                        SELECT max(ends_at) FROM user_suspension
                        WHERE user_id = :user AND starts_at <= :now AND ends_at > :now
                        """)
                .param("user", userId).param("now", Timestamp.from(now))
                .query((rs, n) -> rs.getTimestamp(1)).optional().map(Timestamp::toInstant);
    }
}
