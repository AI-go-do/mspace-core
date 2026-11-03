package kr.ac.chosun.mspace.member.adapter.in.web;

import java.time.Instant;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.member.application.EmailLoginUseCase;
import kr.ac.chosun.mspace.member.application.TokenSessionUseCase;
import kr.ac.chosun.mspace.member.application.EmailLoginUseCase.LoginCommand;
import kr.ac.chosun.mspace.member.application.EmailLoginUseCase.LoginResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 학교 메일 로그인 (FR-AUTH-001). 로그인 전에 부르는 API라 토큰이 필요 없다 */
@RestController
class AuthController {

    private final EmailLoginUseCase login;
    private final TokenSessionUseCase sessions;

    AuthController(EmailLoginUseCase login, TokenSessionUseCase sessions) {
        this.login = login;
        this.sessions = sessions;
    }

    /** 1단계: 인증번호 받기. 예: {"email": "a@chosun.ac.kr"} → 204 */
    @PostMapping("/api/auth/email-codes")
    ResponseEntity<Void> sendCode(@RequestBody EmailCodeRequest request) {
        login.sendCode(request.email());
        return ResponseEntity.noContent().build();
    }

    /** 2단계: 번호 확인 + 토큰 받기. 처음이면 name, studentId도 같이 */
    @PostMapping("/api/auth/login")
    TokenResponse login(@RequestBody LoginRequest request) {
        LoginResult r = login.login(new LoginCommand(request.email(), request.code(), request.name(), request.studentId()));
        return TokenResponse.from(r);
    }

    /**
     * 자동 로그인: 액세스 토큰(1시간)이 끝나면 화면이 refresh 토큰으로 새 한 쌍을 받는다.
     * 예: {"refreshToken": "..."} → 200 (로그인과 같은 모양, 새 refreshToken 포함). 받은 refresh 토큰은 이제 못 쓴다.
     * 없거나·만료·이미 쓴 토큰이면 401 REFRESH_TOKEN_INVALID → 화면은 로그인 화면으로 보낸다
     */
    @PostMapping("/api/auth/refresh")
    TokenResponse refresh(@RequestBody RefreshRequest request) {
        return TokenResponse.from(sessions.refresh(request.refreshToken()));
    }

    /** 로그아웃: refresh 토큰을 끊는다. 화면도 들고 있던 두 토큰을 지운다. 항상 204 */
    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
        sessions.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
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

    record LoginRequest(String email, String code, String name, String studentId) {
    }

    record RefreshRequest(String refreshToken) {
    }

    record TokenResponse(String accessToken, String tokenType, Instant expiresAt, long memberId, boolean newMember,
                         String refreshToken, Instant refreshExpiresAt) {

        static TokenResponse from(LoginResult r) {
            return new TokenResponse(r.accessToken(), "Bearer", r.expiresAt(), r.memberId(), r.newMember(),
                    r.refreshToken(), r.refreshExpiresAt());
        }
    }
}
