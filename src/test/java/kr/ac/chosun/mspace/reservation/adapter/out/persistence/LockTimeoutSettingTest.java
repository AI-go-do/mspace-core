package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 잠금 대기 상한 설정값 검사 (DB 없이 생성자만 확인) */
class LockTimeoutSettingTest {

    @Test
    @DisplayName("잠금 대기 상한이 1ms 미만이면 시작할 때 거부한다 (PostgreSQL에서 0은 무제한 대기)")
    void rejectsBelowOneMillisecond() {
        // given / when / then
        assertThatThrownBy(() -> new JdbcReservationAdapter(null, Duration.ofNanos(500_000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcReservationAdapter(null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잠금 대기 상한이 1ms 이상이면 그대로 받는다")
    void acceptsOneMillisecondOrMore() {
        assertThatCode(() -> new JdbcReservationAdapter(null, Duration.ofMillis(1))).doesNotThrowAnyException();
    }
}
