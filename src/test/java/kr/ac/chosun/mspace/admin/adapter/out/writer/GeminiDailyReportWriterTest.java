package kr.ac.chosun.mspace.admin.adapter.out.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.chosun.mspace.admin.application.DailyReportUseCase.Facts;
import kr.ac.chosun.mspace.admin.application.DailyReportWriter.Written;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase.Alerts;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase.Minutes;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase.Rates;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase.Reservations;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase.Sensor;
import kr.ac.chosun.mspace.admin.application.UsageStatsUseCase.Usage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gemini 하루 운영 보고: 보내는 모양(숫자만), 답 해석, 실패하면 정해진 틀 (가짜 Gemini = JDK HttpServer) */
class GeminiDailyReportWriterTest {

    static final String PATH = "/v1beta/models/gemini-3.5-flash-lite:generateContent";
    static final LocalDate DAY = LocalDate.of(2026, 11, 11);
    static final Usage ROOM = new Usage(1, "1번 방", new Reservations(4, 1, 0, 1, 2, 0, 0, 1),
            new Rates(0.333, 0.667, 0.1), new Minutes(240, 150, 75L), 0, 6,
            new Sensor(100, 820, 1350, 0.12, 1000, 2, 150, 0), new Alerts(1, 1, 1.0, 2, 1, 0.5, 10));
    static final Facts FACTS = new Facts(false, ROOM, List.of(ROOM), Map.of("MESSY", Map.of("OPEN", 1L)), 0,
            Map.of("VIOLATION", 1L), Map.of(), Map.of("CAMERA_DOWN", 1L));

    HttpServer server;
    final AtomicReference<String> sentBody = new AtomicReference<>();
    volatile int status = 200;
    volatile String reply = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            sentBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean ok = ex.getRequestURI().getPath().equals(PATH)
                    && "test-key".equals(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] out = (ok ? reply : "{}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(ok ? status : 404, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    GeminiDailyReportWriter writer() {
        var props = new GeminiDailyReportProperties("http://127.0.0.1:" + server.getAddress().getPort(), null,
                "test-key", Duration.ofSeconds(2));
        return new GeminiDailyReportWriter(props, new TemplateDailyReportWriter());
    }

    static String answer(String json) {
        String escaped = json.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {"candidates":[{"content":{"role":"model","parts":[
                  {"text":"생각","thought":true},{"text":"%s"}]}}]}""".formatted(escaped);
    }

    @Test
    @DisplayName("숫자와 방 이름만 보내고, 받은 3–5줄을 그대로 쓴다 (긴 숫자는 가림)")
    void writes() {
        // given
        reply = answer("{\"lines\":[\"예약 4건 중 2건 이용했어요.\",\"1번 방 노쇼 1건이에요.\",\"장치 고장 1건, 연락 01012345678\"]}");

        // when
        Written w = writer().write(DAY, FACTS);

        // then
        assertThat(w.writer()).isEqualTo("gemini:gemini-3.5-flash-lite");
        assertThat(w.promptVersion()).isEqualTo(GeminiDailyReportWriter.VERSION);
        assertThat(w.lines()).containsExactly("예약 4건 중 2건 이용했어요.", "1번 방 노쇼 1건이에요.", "장치 고장 1건, 연락 [숫자 가림]");
        JsonNode body = JsonMapper.builder().build().readTree(sentBody.get());
        JsonNode input = JsonMapper.builder().build()
                .readTree(body.path("contents").path(0).path("parts").path(0).path("text").asString());
        assertThat(input.path("date").asString()).isEqualTo("2026-11-11");
        assertThat(input.path("facts").path("total").path("reservations").path("noShow").asInt()).isEqualTo(1);
        assertThat(input.path("facts").path("deviceFaults").path("CAMERA_DOWN").asInt()).isEqualTo(1);
        assertThat(body.path("generationConfig").path("responseJsonSchema").path("properties").path("lines")
                .path("maxItems").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("3줄보다 적거나, 429·모양이 틀린 답이면 정해진 틀로 쓴다")
    void fallsBack() {
        reply = answer("{\"lines\":[\"한 줄뿐\"]}");
        assertThat(writer().write(DAY, FACTS).writer()).isEqualTo("template");

        reply = "{\"candidates\":[]}";
        assertThat(writer().write(DAY, FACTS).writer()).isEqualTo("template");

        status = 429;
        reply = "{}";
        Written w = writer().write(DAY, FACTS);
        assertThat(w.writer()).isEqualTo("template");
        assertThat(w.lines().getFirst()).isEqualTo("11월 11일 예약 4건 중 2건 이용, 노쇼 1건, 취소 1건이에요.");
    }

    @Test
    @DisplayName("설정 문자열에 키가 찍히지 않는다")
    void keyNotPrinted() {
        assertThat(new GeminiDailyReportProperties(null, null, "secret-key", null).toString()).doesNotContain("secret-key");
    }
}
