package kr.ac.chosun.mspace.member.application;

import kr.ac.chosun.mspace.member.application.EmailLoginUseCase.LoginResult;

/**
 * 자동 로그인 입력 포트 (FR-AUTH-001). 액세스 토큰(1시간)이 끝나면 화면이 refresh 토큰으로 새로 받는다.
 * 학생은 refresh 토큰 기한(14일) 안에 한 번이라도 쓰면 다시 인증하지 않아도 된다 (쓸 때마다 14일이 새로 시작).
 */
public interface TokenSessionUseCase {

    /**
     * refresh 토큰을 새 액세스 토큰 + 새 refresh 토큰으로 바꾼다. 받은 refresh 토큰은 이제 못 쓴다.
     *
     * @throws kr.ac.chosun.mspace.common.error.BusinessException 없거나·만료·이미 쓴 토큰이면 401.
     *         이미 쓴 토큰이 다시 오면 도난으로 보고 그 회원의 토큰을 모두 끊는다
     */
    LoginResult refresh(String refreshToken);

    /** 로그아웃: 이 refresh 토큰을 끊는다. 없거나 이미 끊긴 토큰이어도 조용히 끝낸다 */
    void logout(String refreshToken);
}
