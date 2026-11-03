package kr.ac.chosun.mspace.member.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.member.application.EmailVerificationPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** email_verification 테이블. 한 줄짜리 SQL이라 엔티티 없이 JdbcClient로 둔다 */
@Component
class JdbcEmailVerificationAdapter implements EmailVerificationPort {

    private final JdbcClient jdbc;

    JdbcEmailVerificationAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String email, String codeHash, Instant createdAt, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO email_verification (email, code_hash, created_at, expires_at)
                        VALUES (:email, :hash, :created, :expires)
                        """)
                .param("email", email)
                .param("hash", codeHash)
                .param("created", Timestamp.from(createdAt))
                .param("expires", Timestamp.from(expiresAt))
                .update();
    }

    @Override
    public Optional<Verification> findLatest(String email) {
        return jdbc.sql("""
                        SELECT id, code_hash, created_at, expires_at, failed_attempts, used_at
                          FROM email_verification
                         WHERE email = :email
                         ORDER BY created_at DESC, id DESC
                         LIMIT 1
                        """)
                .param("email", email)
                .query((rs, n) -> new Verification(
                        rs.getLong("id"),
                        rs.getString("code_hash"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("failed_attempts"),
                        rs.getTimestamp("used_at") != null))
                .optional();
    }

    @Override
    public void addFailedAttempt(long id) {
        jdbc.sql("UPDATE email_verification SET failed_attempts = failed_attempts + 1 WHERE id = :id")
                .param("id", id)
                .update();
    }

    @Override
    public boolean markUsed(long id, Instant usedAt) {
        return jdbc.sql("UPDATE email_verification SET used_at = :usedAt WHERE id = :id AND used_at IS NULL")
                .param("usedAt", Timestamp.from(usedAt))
                .param("id", id)
                .update() == 1;
    }
}
