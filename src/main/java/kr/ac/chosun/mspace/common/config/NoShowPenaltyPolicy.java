package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 노쇼 벌칙 숫자 (FR-RSV-106, 11/4 지영 결정). 코드에 박지 않고 application.yml에서 읽는다.
 *
 * @param count       이 횟수만큼 노쇼하면 정지 (2 → 두 번째 노쇼에서 정지)
 * @param windowDays  노쇼를 세는 기간(일). 지난 정지가 끝난 뒤의 노쇼만 센다 (정지가 끝나면 처음부터)
 * @param suspendDays 정지 기간(일). 이동안 대표자로 예약하는 것도, 참여자로 들어가는 것도 안 된다
 */
@ConfigurationProperties("mspace.reservation.no-show-penalty")
public record NoShowPenaltyPolicy(int count, int windowDays, int suspendDays) {
}
