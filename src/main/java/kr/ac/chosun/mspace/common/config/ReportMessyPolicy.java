package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 어질러짐 신고 받는 시간 (FR-RPT-101, 11/8 지영). 코드에 박지 않고 application.yml에서 읽는다.
 * 입실 뒤 이 시간이 지나면 방 상태는 지금 쓰는 팀 몫이라, 앞 팀 탓으로 신고할 수 없다.
 *
 * @param windowMinutes 입실(checked_in_at) 뒤 어질러짐 신고를 받는 시간(분)
 */
@ConfigurationProperties("mspace.report.messy")
public record ReportMessyPolicy(int windowMinutes) {

    public ReportMessyPolicy {
        if (windowMinutes < 1) {
            throw new IllegalArgumentException("mspace.report.messy.window-minutes는 1 이상이어야 합니다 (%d)"
                    .formatted(windowMinutes));
        }
    }
}
