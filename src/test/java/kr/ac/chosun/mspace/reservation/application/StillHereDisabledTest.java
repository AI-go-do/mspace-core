package kr.ac.chosun.mspace.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StillHereDisabledTest {

    @Test
    @DisplayName("설정으로 꺼 두면 DB를 건드리지 않고 404 STILL_HERE_DISABLED")
    void disabled() {
        SensorPolicy sensor = new SensorPolicy(60, 5, new SensorPolicy.Away(10, 15, 3, false), null);
        StillHereService service = new StillHereService(null, null, sensor,
                Clock.fixed(Instant.parse("2030-03-05T01:40:00Z"), ZoneId.of("Asia/Seoul")));

        assertThatThrownBy(() -> service.stillHere(1, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STILL_HERE_DISABLED));
    }
}
