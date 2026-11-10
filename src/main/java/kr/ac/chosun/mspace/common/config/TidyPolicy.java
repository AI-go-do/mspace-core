package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 두고 간 물건 다시 확인 (FR-DEV-105, 11/10 10차 회의 "2시간 안에 안 가져가면 경고"). application.yml에서 읽는다.
 *
 * @param recheckAfterMinutes  퇴실 뒤 이 시간이 지나면 방 카메라가 한 번 더 본다(분)
 * @param warnMinConfidence    다시 볼 때 확신도가 이 값 이상인 물건이 하나라도 남아 있으면 경고 (0~1).
 *                             11/10 지영: 0.8로 시작하고 모델 비교 결과를 보고 조정
 */
@ConfigurationProperties("mspace.tidy")
public record TidyPolicy(int recheckAfterMinutes, double warnMinConfidence) {

    public TidyPolicy {
        if (warnMinConfidence <= 0 || warnMinConfidence > 1) {
            throw new IllegalArgumentException("mspace.tidy.warn-min-confidence는 0보다 크고 1 이하여야 합니다 (%s)"
                    .formatted(warnMinConfidence));
        }
        if (recheckAfterMinutes < 1) {
            throw new IllegalArgumentException("mspace.tidy.recheck-after-minutes는 1 이상이어야 합니다 (%d)"
                    .formatted(recheckAfterMinutes));
        }
    }
}
