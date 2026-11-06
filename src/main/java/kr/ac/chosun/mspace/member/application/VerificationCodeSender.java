package kr.ac.chosun.mspace.member.application;

/**
 * 인증번호를 사람에게 보내는 창구(나가는 포트).
 * 구현: 메일 서버 설정이 있으면 SMTP 메일, 없으면 로그에 찍기(로컬·테스트·시연).
 */
public interface VerificationCodeSender {

    void send(String email, String code);
}
