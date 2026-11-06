package kr.ac.chosun.mspace.member.adapter.in.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorResponse;
import kr.ac.chosun.mspace.member.application.EmailLoginUseCase;
import kr.ac.chosun.mspace.member.application.TokenSessionUseCase;
import kr.ac.chosun.mspace.member.application.EmailLoginUseCase.LoginCommand;
import kr.ac.chosun.mspace.member.application.EmailLoginUseCase.LoginResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학교 메일 로그인 (FR-AUTH-001·002). 로그인 전에 부르는 API라 토큰이 필요 없다.
 * <p>
 * refresh 토큰은 응답 본문에 넣지 않고 <b>HttpOnly 쿠키</b>로만 준다. 화면의 스크립트가 읽을 수 없어서
 * 같은 출처에서 악성 스크립트가 돌아도 refresh 토큰은 빼 갈 수 없다. 쿠키는 {@code /api/auth}로만 가고
 * {@code SameSite=Strict}라 다른 사이트에서 보낸 요청에는 붙지 않는다.
 * <ul>
 *   <li>{@value #KEEP}: "자동 로그인" 켬. refresh 토큰 기한(14일)만큼 남는다</li>
 *   <li>{@value #SESSION}: 자동 로그인 끔. 브라우저를 닫으면 사라진다</li>
 * </ul>
 * 둘 중 하나만 둔다. refresh하면 받은 쪽 이름 그대로 새 토큰을 넣는다.
 */
@RestController
class AuthController {

    static final String KEEP = "mspace_rt";
    static final String SESSION = "mspace_rt_s";
    private static final String PATH = "/api/auth";

    private final EmailLoginUseCase login;
    private final TokenSessionUseCase sessions;
    private final boolean secure;
    private final Clock clock;

    AuthController(EmailLoginUseCase login, TokenSessionUseCase sessions,
                   @Value("${mspace.auth.cookie-secure:true}") boolean secure, Clock clock) {
        this.login = login;
        this.sessions = sessions;
        this.secure = secure;
        this.clock = clock;
    }

    /** 1단계: 인증번호 받기. 예: {"email": "a@chosun.ac.kr"} → 204 */
    @PostMapping("/api/auth/email-codes")
    ResponseEntity<Void> sendCode(@RequestBody EmailCodeRequest request) {
        login.sendCode(request.email());
        return ResponseEntity.noContent().build();
    }

    /**
     * 2단계: 번호 확인 + 토큰 받기. 처음이면 name, studentId도 같이.
     * remember=false면 refresh 쿠키가 브라우저를 닫을 때 사라진다(기본 true)
     */
    @PostMapping("/api/auth/login")
    ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
        LoginResult r = login.login(new LoginCommand(request.email(), request.code(), request.name(), request.studentId()));
        return withRefreshCookie(r, request.remember() == null || request.remember());
    }

    /**
     * 자동 로그인: 액세스 토큰(1시간)이 끝나거나 화면을 새로 열면, 쿠키의 refresh 토큰으로 새 액세스 토큰을 받는다.
     * 본문은 필요 없다. 쿠키의 refresh 토큰은 새 것으로 바뀌고 받은 것은 이제 못 쓴다.
     * 쿠키가 없거나·만료·이미 쓴 토큰이면 401 REFRESH_TOKEN_INVALID(쿠키도 지운다) → 화면은 로그인 화면으로 보낸다
     */
    @PostMapping("/api/auth/refresh")
    ResponseEntity<?> refresh(@CookieValue(name = KEEP, required = false) String keep,
                              @CookieValue(name = SESSION, required = false) String session) {
        boolean remember = keep != null;
        try {
            return withRefreshCookie(sessions.refresh(remember ? keep : session), remember);
        } catch (BusinessException e) {
            // 못 쓰는 쿠키는 지워서 브라우저가 다시 보내지 않게 한다
            return ResponseEntity.status(e.errorCode().status())
                    .header(HttpHeaders.SET_COOKIE, clear(KEEP), clear(SESSION))
                    .body(new ErrorResponse(e.errorCode().name(), e.getMessage()));
        }
    }

    /** 로그아웃: refresh 토큰을 끊고 쿠키를 지운다. 화면은 메모리의 액세스 토큰을 버린다. 항상 204 */
    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(@CookieValue(name = KEEP, required = false) String keep,
                                @CookieValue(name = SESSION, required = false) String session) {
        sessions.logout(keep);
        sessions.logout(session);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clear(KEEP), clear(SESSION))
                .build();
    }

    /**
     * 토큰 확인 (FR-AUTH-002). 게이트웨이(nginx auth_request)가 /api/agent/로 넘기기 전에 부른다.
     * 토큰이 맞으면 204, 아니면 LoginUser를 만들다가 401
     */
    @GetMapping("/api/auth/check")
    ResponseEntity<Void> check(LoginUser user) {
        return ResponseEntity.noContent().build();
    }

    record EmailCodeRequest(String email) {
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(LoginResult r, boolean remember) {
        ResponseCookie.ResponseCookieBuilder c = base(remember ? KEEP : SESSION, r.refreshToken());
        if (remember) {
            c.maxAge(Duration.between(clock.instant(), r.refreshExpiresAt()).withNanos(0));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, c.build().toString(), clear(remember ? SESSION : KEEP))
                .body(TokenResponse.from(r));
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite("Strict").path(PATH);
    }

    private String clear(String name) {
        return base(name, "").maxAge(0).build().toString();
    }

    record LoginRequest(String email, String code, String name, String studentId, Boolean remember) {
    }

    /** refresh 토큰은 쿠키로만 가서 본문에는 없다. refreshExpiresAt은 화면 안내용 */
    record TokenResponse(String accessToken, String tokenType, Instant expiresAt, long memberId, boolean newMember,
                         Instant refreshExpiresAt) {

        static TokenResponse from(LoginResult r) {
            return new TokenResponse(r.accessToken(), "Bearer", r.expiresAt(), r.memberId(), r.newMember(),
                    r.refreshExpiresAt());
        }
    }
}
