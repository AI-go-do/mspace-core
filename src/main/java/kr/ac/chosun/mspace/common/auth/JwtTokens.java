package kr.ac.chosun.mspace.common.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 로그인 토큰(JWT, HS256)을 만들고 확인한다. 웹 화면·비서·(나중에) 키패드가 같은 방식으로 core를 부른다.
 * <p>
 * 토큰에 담는 것: sub = 회원 id, scope = 할 수 있는 일(공백으로 구분), iss, iat, exp.
 * 학번·이름 같은 개인정보는 담지 않는다 (JWT 본문은 누구나 열어 볼 수 있다).
 */
@Component
public class JwtTokens {

    private static final Logger log = LoggerFactory.getLogger(JwtTokens.class);
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties props;
    private final Clock clock;
    private final byte[] secret;

    public JwtTokens(JwtProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.secret = secretBytes(props.secret());
    }

    private static byte[] secretBytes(String configured) {
        if (configured == null || configured.isBlank()) {
            log.warn("JWT_SECRET이 비어 있어 임시 비밀값을 만듭니다. 서버를 다시 켜면 모든 토큰이 무효가 됩니다 (로컬 전용)");
            byte[] random = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            return random;
        }
        byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET은 32바이트 이상이어야 합니다 (예: openssl rand -base64 48)");
        }
        return bytes;
    }

    /**
     * 토큰을 만든다.
     *
     * @param memberId 회원 id (sub)
     * @param scopes   할 수 있는 일. 예: {@link Scopes#USER}
     */
    public Issued issue(long memberId, List<String> scopes) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.ttl());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(props.issuer())
                .subject(Long.toString(memberId))
                .claim("scope", String.join(" ", scopes))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("토큰 서명 실패", e);
        }
        return new Issued(jwt.serialize(), expiresAt);
    }

    /**
     * 토큰을 확인한다. 서명이 맞고, 우리 서버가 만들었고, 기한이 안 지났을 때만 값을 돌려준다.
     *
     * @return 확인된 토큰 내용. 하나라도 틀리면 빈 값 (왜 틀렸는지는 밖에 알려 주지 않는다)
     */
    public Optional<Verified> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || !clock.instant().isBefore(exp.toInstant())
                    || !props.issuer().equals(claims.getIssuer())) {
                return Optional.empty();
            }
            String scope = claims.getStringClaim("scope");
            List<String> scopes = scope == null || scope.isBlank() ? List.of() : List.of(scope.split(" "));
            return Optional.of(new Verified(Long.parseLong(claims.getSubject()), scopes));
        } catch (ParseException | JOSEException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 만든 토큰과 끝나는 시각 */
    public record Issued(String token, Instant expiresAt) {
    }

    /** 확인된 토큰 내용 */
    public record Verified(long memberId, List<String> scopes) {
    }

    /** scope 이름 모음. 비서·키패드용 좁은 권한은 그 기능을 붙일 때 더한다 */
    public static final class Scopes {
        /** 학생 본인: 자기 예약 만들기·보기·취소 */
        public static final String USER = "user";

        private Scopes() {
        }
    }
}
