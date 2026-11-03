package kr.ac.chosun.mspace.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 토큰 만들기·확인. 스프링 없이 바로 돌린다 */
class JwtTokensTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef-test";
    static final Instant NOW = Instant.parse("2026-11-03T01:00:00Z");

    JwtTokens at(Instant now, String secret, String issuer) {
        return new JwtTokens(new JwtProperties(secret, Duration.ofHours(12), issuer),
                Clock.fixed(now, ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("만든 토큰을 확인하면 회원 id와 scope가 그대로 나온다")
    void roundTrip() {
        JwtTokens tokens = at(NOW, SECRET, "mspace-core");
        JwtTokens.Issued issued = tokens.issue(7, List.of(JwtTokens.Scopes.USER));

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(tokens.verify(issued.token())).hasValueSatisfying(v -> {
            assertThat(v.memberId()).isEqualTo(7L);
            assertThat(v.scopes()).containsExactly("user");
        });
    }

    @Test
    @DisplayName("기한이 지나면 거절")
    void expired() {
        String token = at(NOW, SECRET, "mspace-core").issue(7, List.of("user")).token();
        assertThat(at(NOW.plus(Duration.ofHours(12)), SECRET, "mspace-core").verify(token)).isEmpty();
    }

    @Test
    @DisplayName("다른 비밀값으로 서명했거나, 발급자가 다르거나, 망가진 토큰은 거절")
    void rejectsForeignTokens() {
        String token = at(NOW, SECRET, "mspace-core").issue(7, List.of("user")).token();
        assertThat(at(NOW, SECRET + "-other", "mspace-core").verify(token)).isEmpty();
        assertThat(at(NOW, SECRET, "someone-else").verify(token)).isEmpty();
        assertThat(at(NOW, SECRET, "mspace-core").verify("not-a-jwt")).isEmpty();
        assertThat(at(NOW, SECRET, "mspace-core").verify(token.substring(0, token.length() - 2) + "xx")).isEmpty();
    }

    @Test
    @DisplayName("scope 없는 토큰은 빈 목록")
    void noScope() {
        JwtTokens tokens = at(NOW, SECRET, "mspace-core");
        assertThat(tokens.verify(tokens.issue(7, List.of()).token()))
                .hasValueSatisfying(v -> assertThat(v.scopes()).isEmpty());
    }

    @Test
    @DisplayName("비밀값이 32바이트보다 짧으면 서버가 뜨지 않는다, 비어 있으면 임시값으로 뜬다")
    void secretRules() {
        assertThatThrownBy(() -> at(NOW, "short", "mspace-core")).isInstanceOf(IllegalStateException.class);
        JwtTokens temp = at(NOW, "", "mspace-core");
        assertThat(temp.verify(temp.issue(1, List.of("user")).token())).isPresent();
    }

    @Test
    @DisplayName("유효 시간·발급자가 비어 있으면 12시간·mspace-core")
    void defaults() {
        JwtProperties props = new JwtProperties(SECRET, null, " ");
        assertThat(props.ttl()).isEqualTo(Duration.ofHours(12));
        assertThat(props.issuer()).isEqualTo("mspace-core");
    }
}
