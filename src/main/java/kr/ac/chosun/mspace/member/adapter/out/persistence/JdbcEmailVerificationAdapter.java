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
    public long save(String email, String codeHash, Instant createdAt, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO email_verification (email, code_hash, created_at, expires_at)
                        VALUES (:email, :hash, :created, :expires)
                        RETURNING id
                        """)
                .param("email", email)
                .param("hash", codeHash)
                .param("created", Timestamp.from(createdAt))
                .param("expires", Timestamp.from(expiresAt))
                .query(Long.class)
                .single();
    }

    @Override
    public void lockEmail(String email) {
        // PostgreSQL advisory lock: 주소마다 다른 번호의 자물쇠, 트랜잭션이 끝나면 저절로 풀린다
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:email))").param("email", email).query().singleRow();
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM email_verification WHERE id = :id").param("id", id).update();
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
    public boolean tryAttempt(long id, int maxAttempts) {
        return jdbc.sql("""
                        UPDATE email_verification SET failed_attempts = failed_attempts + 1
                         WHERE id = :id AND failed_attempts < :max AND used_at IS NULL
                        """)
                .param("id", id)
                .param("max", maxAttempts)
                .update() == 1;
    }

    @Override
    public void refundAttempt(long id) {
        jdbc.sql("UPDATE email_verification SET failed_attempts = failed_attempts - 1 WHERE id = :id AND failed_attempts > 0")
                .param("id", id)
                .update();
    }

    @Override
    public boolean markUsed(long id, Instant usedAt) {
        // 그사이 새 번호가 발급됐으면 옛 번호는 쓸 수 없다 (최신 번호만 유효)
        return jdbc.sql("""
                        UPDATE email_verification AS cur
                           SET used_at = :usedAt
                         WHERE cur.id = :id
                           AND cur.used_at IS NULL
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM email_verification AS newer
                                WHERE newer.email = cur.email
                                  AND (newer.created_at, newer.id) > (cur.created_at, cur.id)
                           )
                        """)
                .param("usedAt", Timestamp.from(usedAt))
                .param("id", id)
                .update() == 1;
    }
}
