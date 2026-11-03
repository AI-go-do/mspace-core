package kr.ac.chosun.mspace.common.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 토큰(JWT) 설정. 값은 application.yml → 환경변수(.env)에서 온다.
 *
 * @param secret    서명 비밀값 (32바이트 이상). 비어 있으면 실행할 때마다 새로 만든다(로컬 전용, 재시작하면 토큰 무효)
 * @param ttl       토큰 유효 시간. 중간에 끊을 수 없는 JWT의 약점을 줄이려고 짧게 둔다
 * @param issuer    토큰을 만든 서버 이름 (iss)
 */
@ConfigurationProperties("mspace.auth.jwt")
public record JwtProperties(String secret, Duration ttl, String issuer) {

    /** .env에 JWT_TTL= 처럼 빈 값만 있어도 기본값으로 뜨게 */
    public JwtProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            ttl = Duration.ofHours(12);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "mspace-core";
        }
    }
}
