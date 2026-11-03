package kr.ac.chosun.mspace.member.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** 실제 메일 서버 없이, 보낼 메일 내용만 확인한다 */
class SmtpVerificationCodeSenderTest {

    @Test
    @DisplayName("팀 주소에서 학생 주소로, 제목과 본문에 번호와 유효 시간을 담아 보낸다")
    void sendsCode() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        new SmtpVerificationCodeSender(mailSender, "team@gmail.com").send("hong@chosun.ac.kr", "042137");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        SimpleMailMessage m = sent.getValue();
        assertThat(m.getFrom()).isEqualTo("team@gmail.com");
        assertThat(m.getTo()).containsExactly("hong@chosun.ac.kr");
        assertThat(m.getSubject()).contains("042137");
        assertThat(m.getText()).contains("042137").contains("10분");
    }
}
