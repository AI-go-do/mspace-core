package kr.ac.chosun.mspace.report.adapter.out.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.common.config.TidyPolicy;
import kr.ac.chosun.mspace.report.application.ReportJudge;
import kr.ac.chosun.mspace.report.application.ReportJudge.AirPoint;
import kr.ac.chosun.mspace.report.application.ReportJudge.Case;
import kr.ac.chosun.mspace.report.application.ReportJudge.Judgement;
import kr.ac.chosun.mspace.report.application.ReportJudge.RoomAir;
import kr.ac.chosun.mspace.report.domain.ReportVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 신고 판정기 정확도 측정 (FR-RPT-103, 11/12 지영 "98%까지 올려 보자").
 * 정답을 정해 둔 가짜 사례(src/test/resources/eval/report-cases.json, 이름·학번 없음)를 규칙 판정기와 Gemini에 똑같이 넣는다.
 * 이름이 *Test가 아니라 평소 빌드에서는 돌지 않는다. 돌리는 법:
 * <pre>GEMINI_API_KEY=... EVAL_SET=dev EVAL_RUNS=3 ./mvnw -o test -Dtest=ReportJudgeEval -Dsurefire.failIfNoSpecifiedTests=false</pre>
 * 키가 없으면 규칙 판정기만 잰다. 결과: target/eval/report-judge-eval.md, .json
 * <p>
 * 정답(truth): CONFIRMED(사실이어야 함) · UNVERIFIED(확인 불가여야 함) · CLOSED(실제로는 아님: 확인 불가·허위 둘 다 맞음).
 * 적용은 서비스와 같다: 확신도 0.8 아래면 확인 불가, 허위는 apply-false가 꺼져 있어 확인 불가.
 * 심각 오류 = 사실이 아닌데 사실(억울한 경고). 어질러짐은 camera-warn을 켠 상태로 잰다(꺼져 있으면 늘 확인 불가라 잴 게 없음).
 */
class ReportJudgeEval {

    static final TidyPolicy TIDY = new TidyPolicy(180, 0.8, 10, true);
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final double MIN_CONFIDENCE = 0.8;

    record Sample(String id, String group, String set, String truth, String why, Case c) {
    }

    record Row(Sample s, Judgement j, long millis) {
        ReportVerdict applied() {
            if (j.verdict() == ReportVerdict.FALSE) {
                return ReportVerdict.UNVERIFIED; // apply-false 기본 꺼짐
            }
            return j.verdict() == ReportVerdict.CONFIRMED && j.confidence() < MIN_CONFIDENCE
                    ? ReportVerdict.UNVERIFIED : j.verdict();
        }

        boolean right() {
            return switch (s.truth()) {
                case "CONFIRMED" -> applied() == ReportVerdict.CONFIRMED;
                default -> applied() == ReportVerdict.UNVERIFIED;
            };
        }

        boolean serious() {
            return !"CONFIRMED".equals(s.truth()) && applied() == ReportVerdict.CONFIRMED;
        }

        /** 실제로 아니었는데 허위까지 맞힌 것 (지금은 적용 안 함, 나중에 켤지 정하려고) */
        boolean falseHit() {
            return "CLOSED".equals(s.truth()) && j.verdict() == ReportVerdict.FALSE && j.confidence() >= MIN_CONFIDENCE;
        }

        /** 사실이었는데 허위라고 함 (켜면 정직한 신고자가 경고받음) */
        boolean falseWrong() {
            return !"CLOSED".equals(s.truth()) && j.verdict() == ReportVerdict.FALSE && j.confidence() >= MIN_CONFIDENCE;
        }
    }

    @Test
    @DisplayName("규칙 판정기와 Gemini의 신고 판정 정확도를 같은 사례로 잰다")
    void measure() throws IOException {
        // EVAL_SET: dev(고칠 때 보는 사례, 기본) | holdout(마지막 확인용, 지시문 고칠 때 안 봄) | all. 쉼표로 여러 개
        String set = System.getenv().getOrDefault("EVAL_SET", "dev");
        int runs = Integer.parseInt(System.getenv().getOrDefault("EVAL_RUNS", "1"));
        List<String> sets = List.of(set.split(","));
        List<Sample> samples = load().stream().filter(s -> "all".equals(set) || sets.contains(s.set())).toList();
        Map<String, List<List<Row>>> results = new LinkedHashMap<>();
        results.put(RuleReportJudge.NAME, List.of(run(new RuleReportJudge(TIDY), samples, 0)));

        // 측정용 키를 먼저 쓴다(서버 키와 한도를 나누지 않게, 11/12 지영). 없으면 GEMINI_API_KEY
        String key = System.getenv().getOrDefault("GEMINI_EVAL_API_KEY", System.getenv("GEMINI_API_KEY"));
        String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.5-flash-lite");
        if (key != null && !key.isBlank()) {
            var props = new GeminiReportJudgeProperties(null, model, key, Duration.ofSeconds(20));
            List<List<Row>> all = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                // 무료 한도(분당 요청 수)를 넘지 않게 사이를 둔다
                all.add(run(new GeminiReportJudge(props, TIDY, new RuleReportJudge(TIDY)), samples, 4500));
            }
            results.put("gemini:" + model, all);
        }

        Path out = Path.of("target/eval/report-judge-eval.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report(set, samples, results));
        Files.writeString(Path.of("target/eval/report-judge-eval.json"), JSON.writeValueAsString(raw(results)));
        System.out.println(Files.readString(out));
        assertThat(results.get(RuleReportJudge.NAME).getFirst()).hasSize(samples.size());
    }

    static List<Row> run(ReportJudge judge, List<Sample> samples, long pauseMillis) {
        List<Row> rows = new ArrayList<>();
        for (Sample s : samples) {
            long start = System.nanoTime();
            Judgement j = judge.judge(s.c());
            rows.add(new Row(s, j, (System.nanoTime() - start) / 1_000_000));
            if (pauseMillis > 0 && GeminiReportJudge.asks(s.c())) {
                try {
                    Thread.sleep(pauseMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return rows;
    }

    static List<Sample> load() throws IOException {
        List<Sample> samples = new ArrayList<>();
        try (InputStream in = ReportJudgeEval.class.getResourceAsStream("/eval/report-cases.json")) {
            long n = 0;
            for (JsonNode j : JSON.readTree(in)) {
                String category = j.path("category").asString();
                String content = j.path("content").asString("");
                Case c;
                if ("WINDOW_OPEN".equals(category)) {
                    List<AirPoint> points = new ArrayList<>();
                    for (JsonNode p : j.path("air")) {
                        points.add(new AirPoint(p.path("minute").asInt(), p.path("ppm").asInt(),
                                p.path("temp").isNull() ? null : p.path("temp").asDouble()));
                    }
                    c = new Case(++n, category, content.isEmpty() ? null : content, true, false, null, List.of(),
                            List.of(), false, false, 1L, false, new RoomAir(j.path("reportMinute").asInt(), points));
                } else {
                    List<String> items = new ArrayList<>();
                    j.path("items").forEach(e -> items.add(e.asString()));
                    List<Double> conf = new ArrayList<>();
                    j.path("conf").forEach(e -> conf.add(e.asDouble()));
                    c = new Case(++n, category, content.isEmpty() ? null : content, true, true, j.path("result").asString(),
                            items, conf, j.path("warned").asBoolean(), j.path("retrieved").asBoolean(), 1L, true, null);
                }
                samples.add(new Sample(j.path("id").asString(), j.path("group").asString(), j.path("set").asString("dev"),
                        j.path("truth").asString(), j.path("why").asString(), c));
            }
        }
        return samples;
    }

    static Map<String, Object> raw(Map<String, List<List<Row>>> results) {
        Map<String, Object> all = new LinkedHashMap<>();
        results.forEach((name, runs) -> all.put(name, runs.stream().map(rows -> rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.s().id());
            m.put("truth", r.s().truth());
            m.put("verdict", r.j().verdict().name());
            m.put("confidence", r.j().confidence());
            m.put("applied", r.applied().name());
            m.put("right", r.right());
            m.put("judge", r.j().judge());
            m.put("millis", r.millis());
            m.put("reasons", r.j().reasons());
            return m;
        }).toList()).toList()));
        return all;
    }

    static String report(String set, List<Sample> samples, Map<String, List<List<Row>>> results) {
        StringBuilder md = new StringBuilder();
        md.append("# 신고 판정기 정확도 (사례 ").append(samples.size()).append("개, ").append(set).append(")\n\n");
        md.append("| 판정기 | 판 | 맞춤(판별) | 평균 정확도 | 최저 | 심각 오류(억울한 경고) | 허위 맞힘 / 허위 틀림 | 규칙으로 넘어감 | 평균 시간 | 최대 시간 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        results.forEach((name, runs) -> {
            List<String> per = new ArrayList<>();
            double sum = 0;
            double min = 100;
            int serious = 0;
            int fh = 0;
            int fw = 0;
            int fallback = 0;
            long total = 0;
            long max = 0;
            int calls = 0;
            for (List<Row> rows : runs) {
                long right = rows.stream().filter(Row::right).count();
                per.add(String.valueOf(right));
                double acc = 100.0 * right / rows.size();
                sum += acc;
                min = Math.min(min, acc);
                serious += (int) rows.stream().filter(Row::serious).count();
                fh += (int) rows.stream().filter(Row::falseHit).count();
                fw += (int) rows.stream().filter(Row::falseWrong).count();
                for (Row r : rows) {
                    if (!name.equals(RuleReportJudge.NAME) && GeminiReportJudge.asks(r.s().c())) {
                        calls++;
                        total += r.millis();
                        max = Math.max(max, r.millis());
                        if (RuleReportJudge.NAME.equals(r.j().judge())) {
                            fallback++;
                        }
                    }
                }
            }
            md.append("| %s | %d | %s/%d | %.1f%% | %.1f%% | %d | %d / %d | %d | %s | %s |\n".formatted(name, runs.size(),
                    String.join(",", per), samples.size(), sum / runs.size(), min, serious, fh, fw, fallback,
                    calls == 0 ? "-" : String.format("%.1f초", total / 1000.0 / calls),
                    calls == 0 ? "-" : String.format("%.1f초", max / 1000.0)));
        });
        md.append("\n## 틀린 사례\n\n| 판정기 | 판 | 사례 | 묶음 | 정답 | 판정(확신도) → 적용 | 이유 |\n|---|---|---|---|---|---|---|\n");
        results.forEach((name, runs) -> {
            for (int i = 0; i < runs.size(); i++) {
                for (Row r : runs.get(i)) {
                    if (!r.right()) {
                        md.append("| %s | %d | %s | %s | %s | %s(%.2f) → %s | %s |\n".formatted(name, i + 1, r.s().id(),
                                r.s().group(), r.s().truth(), r.j().verdict(), r.j().confidence(), r.applied(),
                                String.join(" ", r.j().reasons()).replace("|", "/")));
                    }
                }
            }
        });
        return md.toString();
    }
}
