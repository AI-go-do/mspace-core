package kr.ac.chosun.mspace.member.application;

import java.time.Instant;

/**
 * 학교 메일 인증 로그인 입력 포트 (FR-AUTH-001). 웹 어댑터는 이 인터페이스만 안다.
 * 흐름: {@link #sendCode} → 메일로 받은 번호로 {@link #login}. 처음이면 이름·학번을 같이 보내 가입한다.
 */
public interface EmailLoginUseCase {

    /**
     * 학교 메일로 6자리 인증번호를 보낸다.
     *
     * @throws kr.ac.chosun.mspace.common.error.BusinessException 학교 메일이 아니면 400, 1분 안에 다시 보내면 429
     */
    void sendCode(String email);

    /**
     * 번호를 확인하고 토큰을 준다. 가입 이력이 없으면 이름·학번으로 회원을 만든다.
     *
     * @throws kr.ac.chosun.mspace.common.error.BusinessException 번호가 없거나·틀리거나·만료·5번 틀림이면 400,
     *                                                         처음인데 이름·학번이 없으면 400, 학번이 이미 쓰이면 409
     */
    LoginResult login(LoginCommand command);

    /**
     * @param name      처음 가입할 때만 필요 (이미 회원이면 무시)
     * @param studentId 처음 가입할 때만 필요 (이미 회원이면 무시)
     */
    record LoginCommand(String email, String code, String name, String studentId) {
    }

    /**
     * @param newMember 이번에 가입했는지 (화면이 이용 수칙 동의로 보낼지 정할 때 쓴다)
     */
    record LoginResult(String accessToken, Instant expiresAt, long memberId, boolean newMember) {
    }
}
