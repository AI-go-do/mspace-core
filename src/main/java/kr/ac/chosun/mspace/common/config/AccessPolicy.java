package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 키패드 규칙의 숫자와 장치 토큰 (FR-ACC-001). application.yml에서 읽는다.
 *
 * @param openBeforeMinutes 시작 몇 분 전부터 번호가 맞나 (10)
 * @param lockAfterFailures 몇 번 연속 틀리면 잠그나 (5)
 * @param lockSeconds       몇 초 잠그나 (60)
 * @param deviceToken       예전 공용 장치 토큰(모든 방). 장치별 토큰으로 옮기는 동안만 쓴다. 비우면 받지 않는다
 * @param devices           장치별 토큰 "deviceId:roomId:토큰"을 쉼표로 이은 한 줄(MSPACE_DEVICES). 토큰이 그 방에 묶인다
 */
@ConfigurationProperties("mspace.access")
public record AccessPolicy(int openBeforeMinutes, int lockAfterFailures, int lockSeconds, String deviceToken,
                           String devices) {
}
