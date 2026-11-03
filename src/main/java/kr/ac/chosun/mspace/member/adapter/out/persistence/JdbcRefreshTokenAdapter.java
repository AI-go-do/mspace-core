package kr.ac.chosun.mspace.member.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.member.application.RefreshTokenPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** refresh_token 테이블 */
@Component
class JdbcRefreshTokenAdapter implements RefreshTokenPort {

    private final JdbcClient jdbc;

    JdbcRefreshTokenAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(long memberId, String tokenHash, Instant createdAt, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO refresh_token (member_id, token_hash, created_at, expires_at)
                        VALUES (:member, :hash, :created, :expires)
                        """)
                .param("member", memberId)
                .param("hash", tokenHash)
                .param("created", Timestamp.from(createdAt))
                .param("expires", Timestamp.from(expiresAt))
                .update();
    }

    @Override
    public Optional<StoredToken> findForUpdate(String tokenHash) {
        return jdbc.sql("""
                        SELECT id, member_id, expires_at, revoked_at
                          FROM refresh_token
                         WHERE token_hash = :hash
                           FOR UPDATE
                        """)
                .param("hash", tokenHash)
                .query((rs, n) -> new StoredToken(
                        rs.getLong("id"),
                        rs.getLong("member_id"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") != null))
                .optional();
    }

    @Override
    public boolean revoke(long id, Instant at) {
        return jdbc.sql("UPDATE refresh_token SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL")
                .param("at", Timestamp.from(at))
                .param("id", id)
                .update() == 1;
    }

    @Override
    public void revokeAllOf(long memberId, Instant at) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = :at WHERE member_id = :member AND revoked_at IS NULL")
                .param("at", Timestamp.from(at))
                .param("member", memberId)
                .update();
    }

    @Override
    public void revokeByHash(String tokenHash, Instant at) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = :at WHERE token_hash = :hash AND revoked_at IS NULL")
                .param("at", Timestamp.from(at))
                .param("hash", tokenHash)
                .update();
    }
}
