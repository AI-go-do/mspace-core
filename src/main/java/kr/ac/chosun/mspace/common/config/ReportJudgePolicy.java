package kr.ac.chosun.mspace.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 신고 자동 판정 (FR-RPT-103 10차: 관리자 대신 판정기, Q-28). application.yml에서 읽는다.
 *
 * @param waitAfter     신고 뒤 이만큼 지나야 판정한다 (앞 팀 퇴실 직후 방 카메라 결과가 들어올 시간)
 * @param minConfidence 판정기가 사실·허위라고 해도 확신도가 이보다 낮으면 확인 불가(벌칙 없음)로 정한다
 * @param batchSize     한 번 돌 때 판정할 신고 수
 */
@ConfigurationProperties("mspace.report.judge")
public record ReportJudgePolicy(Duration waitAfter, double minConfidence, int batchSize) {

    public ReportJudgePolicy {
        if (waitAfter == null || waitAfter.isNegative() || batchSize < 1) {
            throw new IllegalArgumentException("mspace.report.judge.wait-after는 0 이상, batch-size는 1 이상이어야 합니다");
        }
        if (minConfidence <= 0 || minConfidence > 1) {
            throw new IllegalArgumentException("mspace.report.judge.min-confidence는 0보다 크고 1 이하여야 합니다 (%s)"
                    .formatted(minConfidence));
        }
    }
}
