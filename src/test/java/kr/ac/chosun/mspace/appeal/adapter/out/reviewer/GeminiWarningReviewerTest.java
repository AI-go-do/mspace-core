package kr.ac.chosun.mspace.appeal.adapter.out.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Case;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Decision;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Review;
import kr.ac.chosun.mspace.common.config.TidyPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gemini 판단기: 보내는 모양, 답 해석, 실패하면 규칙 판단기 (가짜 Gemini = JDK HttpServer, 진짜 키 없이) */
class GeminiWarningReviewerTest {

    static final TidyPolicy TIDY = new TidyPolicy(180, 0.8, 10);
    static final String PATH = "/v1beta/models/gemini-3.5-flash-lite:generateContent";
    static final Case CAMERA = new Case(7, "VIOLATION", "CAMERA", "MESSY", "카메라 자동 확인 010-1234-5678",
            "퇴실할 때 우산은 챙겼어요. 다시 봐 주세요.", List.of("TIDY_CHECK"), true, false,
            List.of("umbrella", "bottle"), List.of(0.93, 0.41));

    HttpServer server;
    final AtomicReference<String> sentBody = new AtomicReference<>();
    final AtomicReference<String> sentKey = new AtomicReference<>();
    volatile int status = 200;
    volatile String reply = "";
    /** 처음 몇 번은 503으로 답한다 (Gemini 과부하 흉내) */
    final java.util.concurrent.atomic.AtomicInteger busy = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            sentKey.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            sentBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.incrementAndGet();
            boolean overloaded = busy.getAndDecrement() > 0;
            byte[] out = (ex.getRequestURI().getPath().equals(PATH) && !overloaded ? reply : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(!ex.getRequestURI().getPath().equals(PATH) ? 404 : overloaded ? 503 : status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    GeminiWarningReviewer reviewer() {
        var props = new GeminiReviewerProperties("http://127.0.0.1:" + server.getAddress().getPort(), null,
                "test-key", Duration.ofSeconds(2));
        GeminiWarningReviewer r = new GeminiWarningReviewer(props, TIDY, new RuleWarningReviewer(TIDY));
        r.retryWaitMillis = 1;
        return r;
    }

    static String answer(String json) {
        String escaped = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"candidates":[{"content":{"role":"model","parts":[
                  {"text":"생각","thought":true},{"text":"%s"}]}}]}""".formatted(escaped);
    }

    @Test
    @DisplayName("요청: 키는 헤더로, 지시문·답 모양(JSON 스키마)을 보내고 긴 숫자는 가린다")
    void requestShape() {
        // given
        reply = answer("{\"decision\":\"KEPT\",\"confidence\":0.9,\"reasons\":[\"남아 있었어요.\"],\"evidenceUsed\":[\"TIDY_CHECK\"]}");

        // when
        reviewer().review(CAMERA);

        // then
        assertThat(sentKey.get()).isEqualTo("test-key");
        JsonNode body = JsonMapper.builder().build().readTree(sentBody.get());
        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString()).contains("심사관");
        assertThat(body.path("generationConfig").path("responseMimeType").asString()).isEqualTo("application/json");
        assertThat(body.path("generationConfig").path("responseJsonSchema").path("required").toString())
                .contains("decision", "confidence");
        String facts = body.path("contents").path(0).path("parts").path(0).path("text").asString();
        assertThat(facts).contains("umbrella", "0.93", "\"reportSource\":\"CAMERA\"", "[숫자 가림]")
                .doesNotContain("010-1234-5678");
    }

    @Test
    @DisplayName("답: 생각 문장은 버리고 JSON을 결정으로. 모르는 근거 이름은 빼고 판단기 이름·지시문 버전을 남긴다")
    void parsesAnswer() {
        // given
        reply = answer("""
                {"decision":"KEPT","confidence":0.88,"reasons":["다시 확인 때 우산이 남아 있었어요.","3시간 안에 가져가지 않았어요."],
                 "evidenceUsed":["TIDY_CHECK","CHECKOUT_PHOTO","TIDY_CHECK"]}""");

        // when
        Review r = reviewer().review(CAMERA);

        // then
        assertThat(r.decision()).isEqualTo(Decision.KEPT);
        assertThat(r.confidence()).isEqualTo(0.88);
        assertThat(r.reasons()).containsExactly("다시 확인 때 우산이 남아 있었어요.", "3시간 안에 가져가지 않았어요.");
        assertThat(r.evidenceUsed()).containsExactly("TIDY_CHECK");
        assertThat(r.reviewer()).isEqualTo("gemini:gemini-3.5-flash-lite");
        assertThat(r.promptVersion()).isEqualTo("gemini-v3");
    }

    @Test
    @DisplayName("429(무료 한도)면 규칙 판단기로 본다. 기록의 판단기 이름은 rules")
    void rateLimitFallsBackToRules() {
        // given
        status = 429;
        reply = "{}";

        // when
        Review r = reviewer().review(CAMERA);

        // then: 규칙 판단기 = 확신도 0.93 ≥ 0.8 → 유지
        assertThat(r.reviewer()).isEqualTo("rules");
        assertThat(r.decision()).isEqualTo(Decision.KEPT);
    }

    @Test
    @DisplayName("503(Gemini 과부하)은 두 번까지 다시 해서 Gemini 답을 쓴다")
    void retriesOverload() {
        // given: 처음 두 번 503, 세 번째 정상
        busy.set(2);
        reply = answer("""
                {"decision":"WITHDRAWN","confidence":0.9,"reasons":["다시 봤어요."],"evidenceUsed":["TIDY_CHECK"]}""");

        // when
        Review r = reviewer().review(CAMERA);

        // then
        assertThat(calls.get()).isEqualTo(3);
        assertThat(r.reviewer()).isEqualTo("gemini:gemini-3.5-flash-lite");
        assertThat(r.decision()).isEqualTo(Decision.WITHDRAWN);
    }

    @Test
    @DisplayName("503이 세 번 이어지면 규칙 판단기로 본다. 429는 다시 하지 않는다")
    void overloadThenRules() {
        // given
        busy.set(3);
        reply = "{}";

        // when
        Review r = reviewer().review(CAMERA);

        // then
        assertThat(calls.get()).isEqualTo(3);
        assertThat(r.reviewer()).isEqualTo("rules");

        // given: 429
        calls.set(0);
        status = 429;

        // when
        reviewer().review(CAMERA);

        // then
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("다시 시도 대기 중 인터럽트되면 규칙 판단기로 정하지 않고 실패로 남긴다")
    void interruptedDoesNotFallBack() {
        // given: 503이 이어지고, 대기 전에 인터럽트
        busy.set(3);
        reply = "{}";
        GeminiWarningReviewer r = reviewer();
        r.retryWaitMillis = 1000;
        Thread.currentThread().interrupt();

        // when, then
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> r.review(CAMERA))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("결정 값이 틀리거나 확신도가 0~1 밖이면 규칙 판단기로 본다")
    void badShapeFallsBackToRules() {
        // given
        reply = answer("{\"decision\":\"MAYBE\",\"confidence\":0.9,\"reasons\":[\"음\"],\"evidenceUsed\":[]}");
        GeminiWarningReviewer gemini = reviewer();

        // when · then
        assertThat(gemini.review(CAMERA).reviewer()).isEqualTo("rules");

        reply = answer("{\"decision\":\"WITHDRAWN\",\"confidence\":1.5,\"reasons\":[\"음\"],\"evidenceUsed\":[]}");
        assertThat(gemini.review(CAMERA).reviewer()).isEqualTo("rules");

        reply = answer("{\"decision\":\"WITHDRAWN\",\"confidence\":0.7,\"reasons\":[],\"evidenceUsed\":[]}");
        assertThat(gemini.review(CAMERA).reviewer()).isEqualTo("rules");
    }

    @Test
    @DisplayName("모델 답에 긴 숫자가 섞여도 학생에게 보이는 이유에서는 가린다")
    void masksNumbersInReasons() {
        // given
        reply = answer("{\"decision\":\"WITHDRAWN\",\"confidence\":0.7,\"reasons\":[\"20241234 학생 사유를 봤어요.\"],\"evidenceUsed\":[\"APPEAL\"]}");

        // when
        Review r = reviewer().review(CAMERA);

        // then
        assertThat(r.reasons()).containsExactly("[숫자 가림] 학생 사유를 봤어요.");
    }

    @Test
    @DisplayName("키가 없으면 규칙 판단기, 있으면 Gemini 판단기를 쓴다. 키는 설정 문자열에 찍히지 않는다")
    void picksReviewerByKey() {
        ReviewerConfig config = new ReviewerConfig();

        WarningReviewer none = config.warningReviewer(TIDY, new GeminiReviewerProperties(null, null, " ", null));
        GeminiReviewerProperties keyed = new GeminiReviewerProperties(null, null, "secret-key", null);
        WarningReviewer gemini = config.warningReviewer(TIDY, keyed);

        assertThat(none).isInstanceOf(RuleWarningReviewer.class);
        assertThat(gemini).isInstanceOf(GeminiWarningReviewer.class);
        assertThat(keyed.toString()).doesNotContain("secret-key");
    }
}
