package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 신고 판정 뒤 벌칙 숫자 (FR-RPT-103·104, docs 3.1 제재 단계). 코드에 박지 않고 application.yml에서 읽는다.
 * 사실 판정 경고와 허위 신고 경고에 같은 숫자를 쓴다 (세는 것은 따로).
 *
 * @param count       경고가 이 횟수가 되면 정지 (3 → 세 번째 경고에서 정지)
 * @param windowDays  경고를 세는 기간(일). 지난 정지가 시작된 뒤의 경고만 센다 (정지가 끝나면 처음부터)
 * @param suspendDays 정지 기간(일)
 */
@ConfigurationProperties("mspace.report.penalty")
public record ReportPenaltyPolicy(int count, int windowDays, int suspendDays) {

    public ReportPenaltyPolicy {
        if (count < 1 || windowDays < 1 || suspendDays < 1) {
            throw new IllegalArgumentException("mspace.report.penalty 값은 모두 1 이상이어야 합니다 (count=%d, window-days=%d, suspend-days=%d)"
                    .formatted(count, windowDays, suspendDays));
        }
    }
}
