package kr.ac.chosun.mspace.report.adapter.out.judge;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini 신고 판정기 설정 (FR-RPT-103, 11/12 지영: 창문 열림·어질러짐을 AI가). 키는 경고 철회 판단기와 같은 GEMINI_API_KEY.
 * 키가 비어 있으면 Gemini를 부르지 않고 규칙 판정기만 쓴다.
 *
 * @param baseUrl Gemini API 주소 (테스트는 가짜 서버)
 * @param model   모델 이름
 * @param apiKey  비밀값. .env·GitHub Secrets에만 둔다
 * @param timeout 한 번 부를 때 기다리는 시간
 */
@ConfigurationProperties("mspace.report.gemini")
record GeminiReportJudgeProperties(String baseUrl, String model, String apiKey, Duration timeout) {

    GeminiReportJudgeProperties {
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
        return "GeminiReportJudgeProperties[baseUrl=%s, model=%s, apiKey=%s, timeout=%s]"
                .formatted(baseUrl, model, enabled() ? "***" : "", timeout);
    }
}
