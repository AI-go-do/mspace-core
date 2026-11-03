package kr.ac.chosun.mspace.member.adapter.in.web;

import java.time.Instant;
import kr.ac.chosun.mspace.member.application.EmailLoginUseCase;
import kr.ac.chosun.mspace.member.application.EmailLoginUseCase.LoginCommand;
import kr.ac.chosun.mspace.member.application.EmailLoginUseCase.LoginResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 학교 메일 로그인 (FR-AUTH-001). 로그인 전에 부르는 API라 토큰이 필요 없다 */
@RestController
class AuthController {

    private final EmailLoginUseCase login;

    AuthController(EmailLoginUseCase login) {
        this.login = login;
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
        return new TokenResponse(r.accessToken(), "Bearer", r.expiresAt(), r.memberId(), r.newMember());
    }

    record EmailCodeRequest(String email) {
    }

    record LoginRequest(String email, String code, String name, String studentId) {
    }

    record TokenResponse(String accessToken, String tokenType, Instant expiresAt, long memberId, boolean newMember) {
    }
}
