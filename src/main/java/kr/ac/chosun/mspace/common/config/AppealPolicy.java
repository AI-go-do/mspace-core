package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 경고 철회 요청 (FR-RPT-105, Q-24 11/10 지영 "추천대로"). application.yml에서 읽는다.
 *
 * @param days          경고를 받은 뒤 이 기간(일) 안에만 요청할 수 있다
 * @param minConfidence 판단기가 "유지"라고 해도 확신도가 이 값보다 낮으면 철회한다 (Q-24 ③ 확인 불가 = 철회)
 * @param batchSize     한 번 돌 때 판단할 요청 수 (AI 호출이 몰리지 않게)
 */
@ConfigurationProperties("mspace.appeal")
public record AppealPolicy(int days, double minConfidence, int batchSize) {

    public AppealPolicy {
        if (days < 1 || batchSize < 1) {
            throw new IllegalArgumentException("mspace.appeal.days·batch-size는 1 이상이어야 합니다 (days=%d, batch-size=%d)"
                    .formatted(days, batchSize));
        }
        if (minConfidence <= 0 || minConfidence > 1) {
            throw new IllegalArgumentException("mspace.appeal.min-confidence는 0보다 크고 1 이하여야 합니다 (%s)"
                    .formatted(minConfidence));
        }
    }
}
