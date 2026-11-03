package kr.ac.chosun.mspace.member.adapter.out.mail;

import kr.ac.chosun.mspace.member.application.VerificationCodeSender;
import kr.ac.chosun.mspace.member.domain.EmailCodeRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 실제 메일 발송 (SMTP). 보내는 주소는 팀 Gmail(앱 비밀번호). 값은 서버 .env의 MAIL_* 에만 둔다.
 */
@Component
@ConditionalOnProperty(name = "mspace.mail.enabled", havingValue = "true")
class SmtpVerificationCodeSender implements VerificationCodeSender {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpVerificationCodeSender(JavaMailSender mailSender, @Value("${mspace.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("[M-SPACE] 인증번호 " + code);
        message.setText("""
                M-SPACE 로그인 인증번호는 %s 입니다.
                %d분 안에 입력해 주세요.

                직접 요청하지 않았다면 이 메일은 무시하셔도 됩니다.
                """.formatted(code, EmailCodeRule.VALID_FOR.toMinutes()));
        mailSender.send(message);
    }
}
