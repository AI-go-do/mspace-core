package kr.ac.chosun.mspace.member.application;

import java.time.Instant;
import java.util.Optional;

/** refresh 토큰 기록(refresh_token)을 저장·조회하는 창구(나가는 포트). 원문이 아니라 해시만 다룬다 */
public interface RefreshTokenPort {

    void save(long memberId, String tokenHash, Instant createdAt, Instant expiresAt);

    /**
     * 해시로 기록을 찾고, 지금 트랜잭션이 끝날 때까지 그 줄을 잡는다 (같은 토큰으로 동시에 두 번 바꾸지 못하게).
     * 트랜잭션 안에서만 부른다.
     */
    Optional<StoredToken> findForUpdate(String tokenHash);

    /** @return 이번에 끊었으면 true (이미 끊긴 토큰이면 false) */
    boolean revoke(long id, Instant at);

    /** 이 회원의 살아 있는 토큰을 모두 끊는다 (도난 의심) */
    void revokeAllOf(long memberId, Instant at);

    /** 해시가 같은 살아 있는 토큰을 끊는다 (로그아웃). 없으면 아무것도 안 한다 */
    void revokeByHash(String tokenHash, Instant at);

    record StoredToken(long id, long memberId, Instant expiresAt, boolean revoked) {
    }
}
