package kr.ac.chosun.mspace.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 미리 예약 시각 규칙 (FR-RSV-001). 스프링·DB 없이 돈다 */
class ReservationTimeTest {

    final ReservationPolicy policy = new ReservationPolicy(7, 30, 120);
    /** 지금 = 2026-11-03(화) 10:10 서울 */
    final Instant now = at("2026-11-03T10:10");

    static Instant at(String seoulLocal) {
        return OffsetDateTime.parse(seoulLocal + ":00+09:00").toInstant();
    }

    ErrorCode errorOf(String start, String end) {
        try {
            ReservationTime.of(at(start), at(end), policy, now);
            return null;
        } catch (BusinessException e) {
            return e.errorCode();
        }
    }

    @Test
    @DisplayName("30분 칸에 맞고 2시간 이하, 7일 안이면 만들어진다")
    void valid() {
        // given, when
        ReservationTime t = ReservationTime.of(at("2026-11-04T14:00"), at("2026-11-04T16:00"), policy, now);

        // then
        assertThat(t.start()).isEqualTo(at("2026-11-04T14:00"));
        assertThat(t.end()).isEqualTo(at("2026-11-04T16:00"));
    }

    @Test
    @DisplayName("시작이나 끝이 30분 칸이 아니면 RESERVATION_NOT_ON_SLOT")
    void notOnSlot() {
        assertThat(errorOf("2026-11-04T14:10", "2026-11-04T15:00")).isEqualTo(ErrorCode.RESERVATION_NOT_ON_SLOT);
        assertThat(errorOf("2026-11-04T14:00", "2026-11-04T15:15")).isEqualTo(ErrorCode.RESERVATION_NOT_ON_SLOT);
    }

    @Test
    @DisplayName("길이가 0이거나 거꾸로거나 2시간을 넘으면 RESERVATION_INVALID_LENGTH")
    void invalidLength() {
        assertThat(errorOf("2026-11-04T14:00", "2026-11-04T14:00")).isEqualTo(ErrorCode.RESERVATION_INVALID_LENGTH);
        assertThat(errorOf("2026-11-04T15:00", "2026-11-04T14:00")).isEqualTo(ErrorCode.RESERVATION_INVALID_LENGTH);
        assertThat(errorOf("2026-11-04T14:00", "2026-11-04T16:30")).isEqualTo(ErrorCode.RESERVATION_INVALID_LENGTH);
    }

    @Test
    @DisplayName("지금보다 앞이거나 같은 시작은 RESERVATION_IN_PAST")
    void inPast() {
        assertThat(errorOf("2026-11-03T10:00", "2026-11-03T11:00")).isEqualTo(ErrorCode.RESERVATION_IN_PAST);
    }

    @Test
    @DisplayName("시작이 7일째(오늘+6일)면 자정을 넘겨도 되고, 8일째면 RESERVATION_OUT_OF_RANGE")
    void bookingWindow() {
        assertThat(errorOf("2026-11-09T23:00", "2026-11-10T01:00")).isNull();
        assertThat(errorOf("2026-11-10T00:00", "2026-11-10T01:00")).isEqualTo(ErrorCode.RESERVATION_OUT_OF_RANGE);
    }

    ErrorCode instantErrorOf(String now, String end) {
        try {
            ReservationTime.instant(at(now), at(end), policy);
            return null;
        } catch (BusinessException e) {
            return e.errorCode();
        }
    }

    @Test
    @DisplayName("즉시 시작: 14:17이면 끝은 14:30–16:30, 정각 14:00이면 14:30–16:00 (2시간 뒤를 30분 칸으로 올림)")
    void instantLatestEnd() {
        assertThat(instantErrorOf("2026-11-03T14:17", "2026-11-03T14:30")).isNull();
        assertThat(instantErrorOf("2026-11-03T14:17", "2026-11-03T16:30")).isNull();
        assertThat(instantErrorOf("2026-11-03T14:17", "2026-11-03T17:00")).isEqualTo(ErrorCode.RESERVATION_INVALID_LENGTH);
        assertThat(instantErrorOf("2026-11-03T14:00", "2026-11-03T16:00")).isNull();
        assertThat(instantErrorOf("2026-11-03T14:00", "2026-11-03T16:30")).isEqualTo(ErrorCode.RESERVATION_INVALID_LENGTH);
        assertThat(instantErrorOf("2026-11-03T14:00", "2026-11-03T14:00")).isEqualTo(ErrorCode.RESERVATION_INVALID_LENGTH);
        assertThat(instantErrorOf("2026-11-03T14:17", "2026-11-03T15:15")).isEqualTo(ErrorCode.RESERVATION_NOT_ON_SLOT);
    }

    @Test
    @DisplayName("즉시 시작: 자정 넘김(23:40 → 다음 날 01:30)도 된다, 시작은 초 아래를 버린다")
    void instantOverMidnight() {
        ReservationTime t = ReservationTime.instant(at("2026-11-03T23:40").plusMillis(1500), at("2026-11-04T01:30"), policy);
        assertThat(t.start()).isEqualTo(at("2026-11-03T23:40").plusSeconds(1));
    }
}
