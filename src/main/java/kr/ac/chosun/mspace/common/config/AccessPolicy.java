package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 키패드 규칙의 숫자와 장치 토큰 (FR-ACC-001). application.yml에서 읽는다.
 *
 * @param openBeforeMinutes 시작 몇 분 전부터 번호가 맞나 (10)
 * @param lockAfterFailures 몇 번 연속 틀리면 잠그나 (5)
 * @param lockSeconds       몇 초 잠그나 (60)
 * @param deviceToken       키패드가 Authorization: Bearer 로 보내는 장치 토큰. 비어 있으면 모든 장치 요청을 401로 막는다
 */
@ConfigurationProperties("mspace.access")
public record AccessPolicy(int openBeforeMinutes, int lockAfterFailures, int lockSeconds, String deviceToken) {
}
