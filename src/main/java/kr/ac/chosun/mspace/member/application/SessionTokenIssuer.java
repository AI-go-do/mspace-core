package kr.ac.chosun.mspace.member.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.JwtProperties;
import kr.ac.chosun.mspace.common.auth.JwtTokens;
import kr.ac.chosun.mspace.common.auth.JwtTokens.Issued;
import kr.ac.chosun.mspace.common.auth.JwtTokens.Scopes;
import org.springframework.stereotype.Component;

/** 로그인·자동 로그인에서 같이 쓰는 "액세스 토큰 + refresh 토큰 한 쌍 만들기" */
@Component
class SessionTokenIssuer {

    private static final int REFRESH_BYTES = 32;

    private final JwtTokens tokens;
    private final RefreshTokenPort refreshTokens;
    private final JwtProperties props;
    private final SecureRandom random = new SecureRandom();

    SessionTokenIssuer(JwtTokens tokens, RefreshTokenPort refreshTokens, JwtProperties props) {
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.props = props;
    }

    /** 새 한 쌍을 만들고 refresh 토큰 해시를 저장한다. 원문은 돌려주기만 하고 어디에도 남기지 않는다 */
    Pair issue(long memberId, Instant now) {
        byte[] raw = new byte[REFRESH_BYTES];
        random.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant refreshExpiresAt = now.plus(props.refreshTtl());
        refreshTokens.save(memberId, hash(refresh), now, refreshExpiresAt);
        Issued access = tokens.issue(memberId, List.of(Scopes.USER));
        return new Pair(access.token(), access.expiresAt(), refresh, refreshExpiresAt);
    }

    static String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Pair(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt) {
    }
}
