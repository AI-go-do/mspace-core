package kr.ac.chosun.mspace.member.application;

import java.time.Clock;
import java.time.Instant;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.member.application.EmailLoginUseCase.LoginResult;
import kr.ac.chosun.mspace.member.application.RefreshTokenPort.StoredToken;
import kr.ac.chosun.mspace.member.application.SessionTokenIssuer.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 자동 로그인 (FR-AUTH-001). refresh 토큰은 한 번 쓰면 새 것으로 바뀐다(회전).
 * <p>
 * 이미 바뀐(끊긴) 토큰이 다시 오면, 누가 옛 토큰을 훔쳐 쓰는 것으로 보고 그 회원의 토큰을 모두 끊는다.
 * 진짜 학생도 다시 로그인해야 하지만, 훔친 쪽이 계속 쓰는 것보다 낫다.
 * 이 "모두 끊기"는 401을 던지기 전에 커밋돼야 해서, 트랜잭션은 결과만 돌려주고 예외는 밖에서 던진다.
 */
@Service
class TokenSessionService implements TokenSessionUseCase {

    private static final Logger log = LoggerFactory.getLogger(TokenSessionService.class);

    private final RefreshTokenPort refreshTokens;
    private final SessionTokenIssuer issuer;
    private final TransactionOperations tx;
    private final Clock clock;

    TokenSessionService(RefreshTokenPort refreshTokens, SessionTokenIssuer issuer, TransactionOperations tx,
                        Clock clock) {
        this.refreshTokens = refreshTokens;
        this.issuer = issuer;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 200) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        Instant now = clock.instant();
        String hash = SessionTokenIssuer.hash(refreshToken.strip());

        Outcome outcome = tx.execute(status -> {
            StoredToken stored = refreshTokens.findForUpdate(hash).orElse(null);
            if (stored == null) {
                return Outcome.invalid();
            }
            if (stored.revoked()) {
                refreshTokens.revokeAllOf(stored.memberId(), now);
                return Outcome.reused(stored.memberId());
            }
            if (!now.isBefore(stored.expiresAt())) {
                return Outcome.invalid();
            }
            refreshTokens.revoke(stored.id(), now);
            return Outcome.ok(issuer.issue(stored.memberId(), now), stored.memberId());
        });

        if (outcome.reused()) {
            log.warn("이미 쓴 refresh 토큰이 다시 와서 회원 {}의 토큰을 모두 끊었습니다 (도난 의심)", outcome.memberId());
        }
        if (outcome.pair() == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        Pair p = outcome.pair();
        return new LoginResult(p.accessToken(), p.accessExpiresAt(), outcome.memberId(), false,
                p.refreshToken(), p.refreshExpiresAt());
    }

    @Override
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 200) {
            return;
        }
        refreshTokens.revokeByHash(SessionTokenIssuer.hash(refreshToken.strip()), clock.instant());
    }

    private record Outcome(Pair pair, long memberId, boolean reused) {
        static Outcome ok(Pair pair, long memberId) {
            return new Outcome(pair, memberId, false);
        }

        static Outcome invalid() {
            return new Outcome(null, 0, false);
        }

        static Outcome reused(long memberId) {
            return new Outcome(null, memberId, true);
        }
    }
}
