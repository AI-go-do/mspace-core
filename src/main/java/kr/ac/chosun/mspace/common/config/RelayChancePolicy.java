package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 취소 가능성 숫자 (11/9 지영 "만들어줘", 안건 B안: 개인 기록 없이 칸 정보만). 측정 뒤 조정할 수 있게 설정값으로 둔다.
 *
 * @param historyWeeks 칸 기본 취소율을 셀 지난 기간(주) (8)
 * @param minSamples   같은 요일·시간대 기록이 이보다 적으면 기본 취소율 대신 시작값을 쓴다 (30)
 * @param startRate    기록이 적을 때 쓰는 시작값 (0.15, 추정)
 * @param earlyDays    예약한 때부터 시작까지 이 일수 이상이면 "미리 잡은 예약" (3)
 * @param earlyFactor  미리 잡은 예약 배수 (1.3)
 * @param lateHours    이 시간 안에 잡은 예약은 "직전 예약" (24)
 * @param lateFactor   직전 예약 배수 (0.6)
 * @param maxRate      보여 주는 가능성의 상한 (0.9)
 */
@ConfigurationProperties("mspace.reservation.relay-chance")
public record RelayChancePolicy(int historyWeeks, int minSamples, double startRate, int earlyDays, double earlyFactor,
                                int lateHours, double lateFactor, double maxRate) {
}
