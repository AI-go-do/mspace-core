package kr.ac.chosun.mspace.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminCheckInServiceTest {

    @Test
    @DisplayName("설정이 꺼져 있으면 관리자여도 403 ADMIN_CHECK_IN_OFF")
    void off() {
        AdminCheckInPort port = new AdminCheckInPort() {
            @Override
            public boolean isAdmin(long userId) {
                return true;
            }

            @Override
            public Optional<Target> findOpen(long reservationId, Instant now) {
                throw new AssertionError("꺼져 있으면 예약을 읽지 않는다");
            }

            @Override
            public Optional<String> status(long reservationId) {
                return Optional.of("BOOKED");
            }
        };
        var service = new AdminCheckInService(port, null, new AccessPolicy(10, 5, 60, "", "", false),
                Clock.fixed(Instant.parse("2026-11-03T01:32:00Z"), ZoneId.of("Asia/Seoul")));

        assertThatThrownBy(() -> service.checkIn(6, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ADMIN_CHECK_IN_OFF));
    }
}
