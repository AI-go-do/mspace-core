package kr.ac.chosun.mspace.admin.adapter.out.writer;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import kr.ac.chosun.mspace.admin.application.DailyReportUseCase.Facts;
import kr.ac.chosun.mspace.admin.application.DailyReportWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini가 하루 숫자를 보고 관리자에게 3–5줄로 알려 주는 운영 보고 (11/12 지영 "core 다 AI화").
 * <ul>
 *   <li>보내는 것: 날짜, 방 이름, 숫자(예약·이용·노쇼·신고·경고·철회·장치 고장·CO2)뿐. 이름·학번·글은 보내지 않는다</li>
 *   <li>받는 것: JSON {lines: 문장 3–5개} (responseJsonSchema로 모양을 강제)</li>
 *   <li>숫자를 지어내지 않게 "입력에 있는 숫자만 쓴다"고 지시한다. 틀이 틀리거나 실패하면 정해진 틀(template)로 쓴다</li>
 * </ul>
 */
class GeminiDailyReportWriter implements DailyReportWriter {

    static final String VERSION = "daily-gemini-v2";
    static final String FALLBACK = DailyReportWriter.FALLBACK;

    private static final Logger log = LoggerFactory.getLogger(GeminiDailyReportWriter.class);
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d(?:[\\s-]?+\\d){6,}+");
    private static final int LINE_MAX = 150;

    private final GeminiDailyReportProperties props;
    private final DailyReportWriter fallback;
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    GeminiDailyReportWriter(GeminiDailyReportProperties props, DailyReportWriter fallback) {
        this.props = props;
        this.fallback = fallback;
        HttpClient client = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(props.timeout());
        this.http = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public Written write(LocalDate date, Facts facts) {
        try {
            List<String> lines = parse(http.post()
                    .uri("/v1beta/models/{model}:generateContent", props.model())
                    .header("x-goog-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(request(date, facts)))
                    .retrieve()
                    .body(String.class));
            if (lines != null) {
                return new Written(lines, "gemini:" + props.model(), VERSION);
            }
            log.warn("Gemini 하루 운영 보고 답 모양이 틀림, 정해진 틀로 씀: date={}", date);
        } catch (RuntimeException e) {
            log.warn("Gemini 하루 운영 보고 실패, 정해진 틀로 씀: date={} error={}", date, e.getClass().getSimpleName());
        }
        // 대신 쓴 글은 표시해 둔다. 보관해도 refresh가 지나면 Gemini로 다시 써 본다 (지난 날도 영영 template로 남지 않게)
        Written t = fallback.write(date, facts);
        return new Written(t.lines(), FALLBACK, t.promptVersion());
    }

    Map<String, Object> request(LocalDate date, Facts facts) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("date", date.toString());
        input.put("dayNotOverYet", facts.partial());
        input.put("facts", facts);
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("lines", Map.of("type", "array", "items", Map.of("type", "string"),
                        "minItems", 3, "maxItems", 5)),
                "required", List.of("lines"));
        return Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", PROMPT))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", json.writeValueAsString(input))))),
                "generationConfig", Map.of("temperature", 0, "responseMimeType", "application/json",
                        "responseJsonSchema", schema));
    }

    /** @return 모양이 틀리면 null (정해진 틀로 넘김) */
    List<String> parse(String body) {
        if (body == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : json.readTree(body).path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false) && part.has("text")) {
                text.append(part.path("text").asString());
            }
        }
        if (text.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode l : json.readTree(text.toString()).path("lines")) {
            String s = LONG_NUMBER.matcher(l.asString("").strip()).replaceAll("[숫자 가림]");
            if (!s.isEmpty() && lines.size() < 5) {
                lines.add(s.length() > LINE_MAX ? s.substring(0, LINE_MAX - 1) + "…" : s);
            }
        }
        return lines.size() < 3 ? null : List.copyOf(lines);
    }

    static final String PROMPT = """
            너는 조선대 M-SPACE 스터디룸(방 5개)을 사람 없이 운영하는 AI다. 관리자가 관제 화면에서 하루를 한눈에 보도록 운영 보고를 쓴다.
            입력은 JSON 하나다. facts.total은 전체, facts.rooms는 방별 이용 통계, reports는 신고 종류→판정→건수, warnings는 경고,
            appeals는 경고 철회 요청 결과, deviceFaults는 장치 고장이다. aiActions는 AI가 숫자 규칙으로 스스로 처리한 일이다:
            VENT_SOON 미리 환기 알림, ROOM_HOT 예약 전 더움 안내, OVERTIME 시간 넘김 안내, SENSOR_DOWN 센서 고장 기록,
            UNBOOKED 예약 없는 사용 기록. dayNotOverYet이 true면 하루가 아직 안 끝난 숫자다.

            쓰는 법
            1. 한국어 해요체 문장 3~5개. 한 문장은 80자 안.
            2. 첫 문장은 하루 이용을 한 줄로(예약·이용·노쇼). 나머지는 관리자가 알아야 할 것을 중요한 순서로: 노쇼가 많은 방,
               대기 중인 신고, 경고·정지, 장치 고장, CO2가 기준을 자주 넘은 방.
               aiActions가 있으면 한 문장으로 "오늘 AI가 미리 환기 3번, 더움 안내 2번 했어요"처럼 종류와 횟수를 쓴다.
               센서 고장·예약 없는 사용은 관리자가 확인할 일이라 그 문장에 같이 쓴다.
            3. 입력에 있는 숫자만 쓴다. 숫자를 지어내거나 계산이 필요한 비율을 새로 만들지 않는다. 비율은 rates에 있는 값만 %로 바꿔 쓴다.
            4. 별일 없으면 별일 없다고 쓴다. 과장하지 않는다.
            5. 사람 이름·학번은 입력에 없고, 쓰지도 않는다.
            """;
}
