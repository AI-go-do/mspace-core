package kr.ac.chosun.mspace.member.adapter.out.mail;

import kr.ac.chosun.mspace.member.application.VerificationCodeSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 메일을 보내지 않고 번호를 로그에 찍는다. 로컬·테스트용, 그리고 메일 계정이 준비되기 전 시연용.
 * MAIL_ENABLED=true면 이 대신 {@link SmtpVerificationCodeSender}가 쓰인다.
 */
@Component
// true가 아니면 전부 여기로 (.env에 MAIL_ENABLED= 처럼 빈 값이어도 서버가 뜨게)
@ConditionalOnExpression("'${mspace.mail.enabled:false}' != 'true'")
class LoggingVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationCodeSender.class);

    LoggingVerificationCodeSender() {
        log.warn("메일 발송이 꺼져 있어 인증번호를 로그에 찍습니다 (MAIL_ENABLED=true로 켜기)");
    }

    @Override
    public void send(String email, String code) {
        log.info("[인증번호] {} → {}", email, code);
    }
}
