package kr.ac.chosun.mspace.admin.adapter.out.writer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini 하루 운영 보고 설정. 키는 판정기들과 같은 GEMINI_API_KEY. 비어 있으면 정해진 틀(template)로만 쓴다.
 *
 * @param baseUrl Gemini API 주소 (테스트는 가짜 서버)
 * @param model   모델 이름
 * @param apiKey  비밀값. .env·GitHub Secrets에만 둔다
 * @param timeout 한 번 부를 때 기다리는 시간
 */
@ConfigurationProperties("mspace.admin.daily-report.gemini")
record GeminiDailyReportProperties(String baseUrl, String model, String apiKey, Duration timeout) {

    GeminiDailyReportProperties {
        baseUrl = blank(baseUrl) ? "https://generativelanguage.googleapis.com" : baseUrl;
        model = blank(model) ? "gemini-3.5-flash-lite" : model;
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }

    boolean enabled() {
        return !blank(apiKey);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 키가 로그·예외 메시지에 찍히지 않게 */
    @Override
    public String toString() {
        return "GeminiDailyReportProperties[baseUrl=%s, model=%s, apiKey=%s, timeout=%s]"
                .formatted(baseUrl, model, enabled() ? "***" : "", timeout);
    }
}
