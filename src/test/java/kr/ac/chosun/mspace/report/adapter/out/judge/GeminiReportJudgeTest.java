package kr.ac.chosun.mspace.report.adapter.out.judge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.chosun.mspace.common.config.TidyPolicy;
import kr.ac.chosun.mspace.report.application.ReportJudge.AirPoint;
import kr.ac.chosun.mspace.report.application.ReportJudge.Case;
import kr.ac.chosun.mspace.report.application.ReportJudge.Judgement;
import kr.ac.chosun.mspace.report.application.ReportJudge.RoomAir;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gemini 신고 판정기: 보내는 모양, 부르는 신고·안 부르는 신고, 규칙보다 무겁게 안 정함, 실패하면 규칙 (가짜 Gemini = JDK HttpServer) */
class GeminiReportJudgeTest {

    static final TidyPolicy CAMERA_ON = new TidyPolicy(180, 0.8, 10, true);
    static final TidyPolicy CAMERA_OFF = new TidyPolicy(180, 0.8, 10, false);
    static final String PATH = "/v1beta/models/gemini-3.5-flash-lite:generateContent";
    static final RoomAir AIR = new RoomAir(30, List.of(new AirPoint(-5, 1150, 23.5), new AirPoint(5, 520, 20.1),
            new AirPoint(15, 440, 19.0)));
    static final Case WINDOW = new Case(1, "WINDOW_OPEN", "창문 열려 있어요 010-1234-5678", true, false, null, List.of(),
            List.of(), false, false, 10L, false, AIR);
    static final Case MESSY = new Case(2, "MESSY", "컵이랑 병이 있어요 010.1234.5678", true, true, "LEFT_ITEMS", List.of("cup", "bottle"),
            List.of(0.95, 0.4), false, false, 10L, false, null);

    HttpServer server;
    final AtomicReference<String> sentBody = new AtomicReference<>();
    final AtomicReference<String> sentKey = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();
    volatile int status = 200;
    volatile String reply = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            sentKey.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            sentBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean ok = ex.getRequestURI().getPath().equals(PATH);
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

    GeminiReportJudge judge(TidyPolicy tidy) {
        var props = new GeminiReportJudgeProperties("http://127.0.0.1:" + server.getAddress().getPort(), null,
                "test-key", Duration.ofSeconds(2));
        return new GeminiReportJudge(props, tidy, new RuleReportJudge(tidy));
    }

    static String answer(String json) {
        String escaped = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"candidates":[{"content":{"role":"model","parts":[
                  {"text":"생각","thought":true},{"text":"%s"}]}}]}""".formatted(escaped);
    }

    static String verdict(String v, double conf) {
        return answer("{\"verdict\":\"%s\",\"confidence\":%s,\"reasons\":[\"이유예요.\"],\"evidenceUsed\":[\"REPORT\",\"TIDY_CHECK\",\"ETC\"]}"
                .formatted(v, conf));
    }

    @Test
    @DisplayName("창문 열림: 키는 헤더로, 신고 글은 빼고 방 CO2·온도만 보내고 Gemini 판정을 그대로 쓴다")
    void windowRequestAndAnswer() {
        // given
        reply = verdict("CONFIRMED", 0.9);

        // when
        Judgement j = judge(CAMERA_OFF).judge(WINDOW);

        // then
        assertThat(sentKey.get()).isEqualTo("test-key");
        JsonNode body = JsonMapper.builder().build().readTree(sentBody.get());
        assertThat(body.path("generationConfig").path("responseMimeType").asString()).isEqualTo("application/json");
        JsonNode facts = JsonMapper.builder().build()
                .readTree(body.path("contents").path(0).path("parts").path(0).path("text").asString());
        assertThat(facts.has("reportContent")).isFalse();
        assertThat(sentBody.get()).doesNotContain("창문 열려 있어요");
        assertThat(facts.path("reportMinuteAfterCheckout").asInt()).isEqualTo(30);
        assertThat(facts.path("roomAir")).hasSize(3);
        assertThat(facts.path("roomAir").path(1).path("co2ppm").asInt()).isEqualTo(520);
        assertThat(facts.has("cameraItems")).isFalse();
        assertThat(j.verdict()).isEqualTo(ReportVerdict.CONFIRMED);
        assertThat(j.confidence()).isEqualTo(0.9);
        assertThat(j.judge()).isEqualTo("gemini:gemini-3.5-flash-lite");
        assertThat(j.promptVersion()).isEqualTo(GeminiReportJudge.VERSION);
        // 보내지 않은 신고 글·카메라는 근거로 남기지 않는다 (Gemini가 REPORT·TIDY_CHECK라고 해도 SENSOR만)
        assertThat(j.evidenceUsed()).containsExactly("SENSOR");
    }

    @Test
    @DisplayName("창문 열림: 허위 답도 그대로 낸다 (적용할지는 apply-false로 core가 정함)")
    void windowFalsePassedThrough() {
        // given
        reply = verdict("FALSE", 0.85);

        // when
        Judgement j = judge(CAMERA_OFF).judge(WINDOW);

        // then
        assertThat(j.verdict()).isEqualTo(ReportVerdict.FALSE);
    }

    @Test
    @DisplayName("센서 값이 3묶음보다 적거나 없으면, 비품 고장이면 Gemini를 부르지 않고 규칙(확인 불가)")
    void notAskedWithoutEvidence() {
        // given
        reply = verdict("CONFIRMED", 0.99);
        Case fewAir = new Case(3, "WINDOW_OPEN", "창문", true, false, null, List.of(), List.of(), false, false, 10L,
                false, new RoomAir(10, List.of(new AirPoint(0, 800, 22.0))));
        Case noAir = new Case(4, "WINDOW_OPEN", "창문", true, false, null, List.of(), List.of(), false, false, null,
                false, null);
        Case broken = new Case(5, "BROKEN", "의자 부러짐", true, false, null, List.of(), List.of(), false, false, 10L,
                false, null);

        // when
        List<Judgement> out = List.of(judge(CAMERA_ON).judge(fewAir), judge(CAMERA_ON).judge(noAir),
                judge(CAMERA_ON).judge(broken));

        // then
        assertThat(calls.get()).isZero();
        assertThat(out).allSatisfy(j -> {
            assertThat(j.verdict()).isEqualTo(ReportVerdict.UNVERIFIED);
            assertThat(j.judge()).isEqualTo("rules");
        });
    }

    @Test
    @DisplayName("어질러짐: 카메라 경고가 꺼져 있으면 Gemini가 사실이라 해도 확인 불가 (규칙보다 무겁게 정하지 않음)")
    void messyNotHeavierThanRules() {
        // given
        reply = verdict("CONFIRMED", 0.97);

        // when
        Judgement j = judge(CAMERA_OFF).judge(MESSY);

        // then
        assertThat(calls.get()).isEqualTo(1);
        assertThat(j.verdict()).isEqualTo(ReportVerdict.UNVERIFIED);
        JsonNode facts = JsonMapper.builder().build().readTree(JsonMapper.builder().build().readTree(sentBody.get())
                .path("contents").path(0).path("parts").path(0).path("text").asString());
        // 물건 이름은 보내지 않고 "남아 있나"와 확신도만 (11/12 지영)
        assertThat(facts.has("cameraItems")).isFalse();
        assertThat(sentBody.get()).doesNotContain("cup").doesNotContain("bottle");
        assertThat(facts.path("cameraItemCount").asInt()).isEqualTo(2);
        assertThat(facts.path("cameraLeftConfidence").asDouble()).isEqualTo(MESSY.previousConfidences().stream()
                .mapToDouble(Double::doubleValue).max().orElseThrow());
        assertThat(facts.path("reportContent").asString()).isEqualTo("컵이랑 병이 있어요 [숫자 가림]");
        assertThat(facts.has("roomAir")).isFalse();
    }

    @Test
    @DisplayName("어질러짐: 카메라 확신도에 빈 값(null)이 섞여 있어도 Gemini에 묻고, 남아 있을 확률은 빈 값을 빼고 고른다 (리뷰 수연)")
    void messyNullConfidenceSkipped() {
        // given
        reply = verdict("UNVERIFIED", 0.9);
        Case withNull = new Case(7, "MESSY", "컵 있어요", true, true, "LEFT_ITEMS", List.of("unknown", "cup"),
                java.util.Arrays.asList(null, 0.9), false, false, 10L, false, null);

        // when
        Judgement j = judge(CAMERA_OFF).judge(withNull);

        // then
        assertThat(calls.get()).isEqualTo(1);
        assertThat(j.judge()).startsWith("gemini:");
        JsonNode facts = JsonMapper.builder().build().readTree(JsonMapper.builder().build().readTree(sentBody.get())
                .path("contents").path(0).path("parts").path(0).path("text").asString());
        assertThat(facts.path("cameraLeftConfidence").asDouble()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("어질러짐: 카메라 경고가 켜져 있고 규칙도 사실이면 사실, 확신도는 카메라 확신도를 넘지 않는다")
    void messyConfirmedCapped() {
        // given
        reply = verdict("CONFIRMED", 0.99);

        // when
        Judgement j = judge(CAMERA_ON).judge(MESSY);

        // then
        assertThat(j.verdict()).isEqualTo(ReportVerdict.CONFIRMED);
        assertThat(j.confidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("어질러짐: 규칙이 사실이어도 Gemini가 글과 카메라가 안 맞는다고 보면 확인 불가")
    void messyGeminiCanLower() {
        // given
        reply = verdict("UNVERIFIED", 0.7);

        // when
        Judgement j = judge(CAMERA_ON).judge(MESSY);

        // then
        assertThat(j.verdict()).isEqualTo(ReportVerdict.UNVERIFIED);
        assertThat(j.judge()).startsWith("gemini:");
    }

    @Test
    @DisplayName("어질러짐: 이미 경고받았거나 찾으러 왔으면 Gemini를 부르지 않는다")
    void messySettledNotAsked() {
        // given
        reply = verdict("CONFIRMED", 0.99);
        Case warned = new Case(6, "MESSY", "컵", true, true, "LEFT_ITEMS", List.of("cup"), List.of(0.95), true, false,
                10L, true, null);

        // when
        Judgement j = judge(CAMERA_ON).judge(warned);

        // then
        assertThat(calls.get()).isZero();
        assertThat(j.verdict()).isEqualTo(ReportVerdict.UNVERIFIED);
    }

    @Test
    @DisplayName("429·모양이 틀린 답·OPEN 답이면 규칙 판정기 결과를 쓴다")
    void fallsBackToRules() {
        // given / when / then
        status = 429;
        reply = "{}";
        assertThat(judge(CAMERA_ON).judge(WINDOW).judge()).isEqualTo("rules");

        status = 200;
        reply = answer("{\"verdict\":\"MAYBE\",\"confidence\":0.5,\"reasons\":[\"몰라요\"],\"evidenceUsed\":[]}");
        assertThat(judge(CAMERA_ON).judge(WINDOW).judge()).isEqualTo("rules");

        reply = verdict("OPEN", 0.5);
        assertThat(judge(CAMERA_ON).judge(WINDOW).judge()).isEqualTo("rules");

        reply = answer("{\"verdict\":\"CONFIRMED\",\"confidence\":1.5,\"reasons\":[\"이유\"],\"evidenceUsed\":[]}");
        assertThat(judge(CAMERA_ON).judge(WINDOW).judge()).isEqualTo("rules");

        reply = answer("{\"verdict\":\"CONFIRMED\",\"confidence\":0.9,\"reasons\":{\"x\":\"이유\"},\"evidenceUsed\":[]}");
        assertThat(judge(CAMERA_ON).judge(WINDOW).judge()).isEqualTo("rules");

        reply = answer("{\"verdict\":\"CONFIRMED\",\"confidence\":0.9,\"reasons\":[\"이유\"]}");
        assertThat(judge(CAMERA_ON).judge(WINDOW).judge()).isEqualTo("rules");
    }

    @Test
    @DisplayName("설정 문자열에 키가 찍히지 않는다")
    void keyNotPrinted() {
        var props = new GeminiReportJudgeProperties(null, null, "secret-key", null);
        assertThat(props.toString()).doesNotContain("secret-key");
    }
}
