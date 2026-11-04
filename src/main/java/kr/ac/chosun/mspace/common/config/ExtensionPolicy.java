package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 연장하기 숫자 (FR-RSV-105). 코드에 박지 않고 application.yml에서 읽는다.
 *
 * @param maxCount          이어진 사용 한 번에 연장할 수 있는 횟수 (1 → 원래 예약 + 연장 1회)
 * @param openBeforeMinutes 종료 몇 분 전부터 연장할 수 있나 (10 → 종료 10분 전 ~ 종료 전)
 * @param maxMinutes        한 번에 늘릴 수 있는 최대 시간(분). 원래 종료 시각부터 30분 단위로 센다
 * @param chainGapMinutes   같은 대표자의 직전 예약이 끝난 뒤 이 시간 안에 시작하면 "이어진 사용"으로 본다 (연장 제한 우회 막기)
 */
@ConfigurationProperties("mspace.reservation.extension")
public record ExtensionPolicy(int maxCount, int openBeforeMinutes, int maxMinutes, int chainGapMinutes) {
}
