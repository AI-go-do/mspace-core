package kr.ac.chosun.mspace.admin.adapter.out.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import kr.ac.chosun.mspace.admin.application.DailyReportPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDailyReportAdapter implements DailyReportPort {

    private final JdbcClient jdbc;

    JdbcDailyReportAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Map<String, Long>> reports(Instant from, Instant to) {
        Map<String, Map<String, Long>> out = new TreeMap<>();
        jdbc.sql("""
                        SELECT category, verdict, count(*) AS n FROM report
                        WHERE created_at >= :from AND created_at < :to
                        GROUP BY category, verdict
                        """)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query(rs -> {
                    out.computeIfAbsent(rs.getString("category"), k -> new TreeMap<>())
                            .put(rs.getString("verdict"), rs.getLong("n"));
                });
        return out;
    }

    @Override
    public long autoJudged(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT count(*) FROM report p JOIN report_judgement j ON j.report_id = p.id
                        WHERE p.created_at >= :from AND p.created_at < :to
                        """)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query(Long.class).single();
    }

    @Override
    public Map<String, Long> warnings(Instant from, Instant to) {
        return counts("SELECT reason AS k, count(*) AS n FROM user_warning WHERE created_at >= :from AND created_at < :to GROUP BY reason",
                from, to);
    }

    @Override
    public Map<String, Long> appeals(Instant from, Instant to) {
        return counts("""
                SELECT status AS k, count(*) AS n FROM warning_appeal
                WHERE (status <> 'PENDING' AND decided_at >= :from AND decided_at < :to)
                   OR (status = 'PENDING' AND created_at >= :from AND created_at < :to)
                GROUP BY status
                """, from, to);
    }

    @Override
    public Map<String, Long> deviceFaults(Instant from, Instant to) {
        return counts("SELECT kind AS k, count(*) AS n FROM device_fault WHERE started_at >= :from AND started_at < :to GROUP BY kind",
                from, to);
    }

    private Map<String, Long> counts(String sql, Instant from, Instant to) {
        Map<String, Long> out = new TreeMap<>();
        jdbc.sql(sql).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query(rs -> {
                    out.put(rs.getString("k"), rs.getLong("n"));
                });
        return out;
    }

    @Override
    public Optional<Saved> saved(LocalDate date) {
        return jdbc.sql("SELECT lines, writer, facts_hash, generated_at FROM daily_report WHERE report_date = :d")
                .param("d", Date.valueOf(date))
                .query((rs, n) -> new Saved(Arrays.asList((String[]) rs.getArray("lines").getArray()),
                        rs.getString("writer"), rs.getString("facts_hash"), rs.getTimestamp("generated_at").toInstant()))
                .optional();
    }

    @Override
    public void save(LocalDate date, List<String> lines, String writer, String promptVersion, String factsHash,
                     Instant generatedAt) {
        // 관리자 둘이 같이 열면 둘 다 만들 수 있다. 더 늦게 만든 글만 남긴다
        jdbc.sql("""
                        INSERT INTO daily_report (report_date, lines, writer, prompt_version, facts_hash, generated_at)
                        VALUES (:d, :lines, :writer, :version, :hash, :at)
                        ON CONFLICT (report_date) DO UPDATE
                        SET lines = EXCLUDED.lines, writer = EXCLUDED.writer, prompt_version = EXCLUDED.prompt_version,
                            facts_hash = EXCLUDED.facts_hash, generated_at = EXCLUDED.generated_at
                        WHERE daily_report.generated_at < EXCLUDED.generated_at
                        """)
                .param("d", Date.valueOf(date)).param("lines", lines.toArray(String[]::new)).param("writer", writer)
                .param("version", promptVersion).param("hash", factsHash).param("at", Timestamp.from(generatedAt))
                .update();
    }
}
