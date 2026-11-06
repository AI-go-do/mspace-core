package kr.ac.chosun.mspace.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MinutesLeftTest {

    private static final Instant END = Instant.parse("2030-03-05T09:00:00Z");

    @Test
    @DisplayName("남은 시간의 초는 올려서 분으로 쓰고, 1분 ~ 설정값 사이로 자른다")
    void roundsUpAndClamps() {
        assertThat(JdbcReminderAdapter.minutesLeft(END.minusSeconds(600), END, 10)).isEqualTo(10);
        assertThat(JdbcReminderAdapter.minutesLeft(END.minusSeconds(270), END, 10)).isEqualTo(5);   // 4분 30초
        assertThat(JdbcReminderAdapter.minutesLeft(END.minusSeconds(300), END, 10)).isEqualTo(5);
        assertThat(JdbcReminderAdapter.minutesLeft(END.minusSeconds(5), END, 10)).isEqualTo(1);
        assertThat(JdbcReminderAdapter.minutesLeft(END.minusMillis(120_500), END, 10)).isEqualTo(3);   // 2분 0.5초
        assertThat(JdbcReminderAdapter.minutesLeft(END.minusSeconds(120), END, 10)).isEqualTo(2);
        assertThat(JdbcReminderAdapter.minutesLeft(END.minusSeconds(900), END, 10)).isEqualTo(10);
    }
}
