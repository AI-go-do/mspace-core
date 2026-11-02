package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예약 규칙의 숫자들. 코드에 박지 않고 application.yml에서 읽는다 (시범운영 후 조정 가능하게).
 *
 * @param bookingWindowDays 오늘을 포함해 며칠 안에 시작하는 예약만 받나 (7 → 오늘 ~ 오늘+6일)
 * @param slotMinutes       예약 단위(분)
 */
@ConfigurationProperties("mspace.reservation")
public record ReservationPolicy(int bookingWindowDays, int slotMinutes) {
}
