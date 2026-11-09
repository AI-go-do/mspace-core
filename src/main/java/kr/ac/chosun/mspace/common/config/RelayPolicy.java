package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 빈방 릴레이 숫자 (Q-18, 11/9 지영). 코드에 박지 않고 application.yml에서 읽는다.
 *
 * @param maxWaits          한 사람이 동시에 걸 수 있는 기다리기 수 (3)
 * @param holdMinutes       넘겨받은 사람만 예약할 수 있는 시간(분). 지나면 다음 사람 (5)
 * @param stopBeforeMinutes 시작 이 분 전부터는 넘기지 않고 그냥 빈 칸으로 푼다. 기다리기도 못 건다 (30)
 */
@ConfigurationProperties("mspace.reservation.relay")
public record RelayPolicy(int maxWaits, int holdMinutes, int stopBeforeMinutes) {
}
