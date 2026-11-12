package kr.ac.chosun.mspace.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관리자 하루 운영 보고 (11/12 지영 "core 다 AI화"). application.yml에서 읽는다.
 *
 * @param refresh 오늘 보고는 이 시간이 지나야 AI 요약을 새로 만든다 (관제 화면이 자주 열려도 무료 한도를 아끼게).
 *                지난 날은 그날이 끝난 뒤 한 번 만든 요약을 계속 쓴다
 */
@ConfigurationProperties("mspace.admin.daily-report")
public record DailyReportPolicy(Duration refresh) {

    public DailyReportPolicy {
        refresh = refresh == null ? Duration.ofHours(1) : refresh;
        if (refresh.isNegative()) {
            throw new IllegalArgumentException("mspace.admin.daily-report.refresh는 0 이상이어야 합니다");
        }
    }
}
