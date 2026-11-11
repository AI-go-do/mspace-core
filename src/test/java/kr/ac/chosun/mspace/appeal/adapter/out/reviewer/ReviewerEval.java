package kr.ac.chosun.mspace.appeal.adapter.out.reviewer;

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
import kr.ac.chosun.mspace.appeal.application.WarningReviewer;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Case;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Decision;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Review;
import kr.ac.chosun.mspace.common.config.TidyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 경고 철회 판단기 정확도 측정 (FR-RPT-105, 11/11 지영 "정확도 재기").
 * 정답을 정해 둔 가짜 사례(src/test/resources/eval/appeal-cases.json, 이름·학번 없음)를 규칙 판단기와 Gemini에 똑같이 넣는다.
 * 이름이 *Test가 아니라 평소 빌드에서는 돌지 않는다. 돌리는 법:
 * <pre>GEMINI_API_KEY=... ./mvnw -o test -Dtest=ReviewerEval -Dsurefire.failIfNoSpecifiedTests=false</pre>
 * 키가 없으면 규칙 판단기만 잰다. 결과 표: target/eval/reviewer-eval.md
 */
class ReviewerEval {

    static final TidyPolicy TIDY = new TidyPolicy(180, 0.8, 10);
    static final JsonMapper JSON = JsonMapper.builder().build();

    /** 서비스와 같은 규칙: 판단기가 "유지"라도 확신도가 이보다 낮으면 철회 (mspace.appeal.min-confidence) */
    static final double MIN_CONFIDENCE = 0.8;

    record Sample(String id, String group, String set, Decision truth, String why, Case c) {
    }

    record Row(Sample s, Review r, long millis) {
        /** 학생에게 실제로 적용되는 결정 (AppealReviewService와 같은 규칙) */
        Decision applied() {
            return r.decision() == Decision.KEPT && r.confidence() < MIN_CONFIDENCE ? Decision.WITHDRAWN : r.decision();
        }

        boolean right() {
            return applied() == s.truth();
        }

        /** 철회해야 하는데 유지 = 잘못된 벌칙. 가장 심각한 오류로 따로 센다 */
        boolean serious() {
            return s.truth() == Decision.WITHDRAWN && applied() == Decision.KEPT;
        }
    }

    @Test
    @DisplayName("규칙 판단기와 Gemini의 경고 철회 판단 정확도를 같은 사례로 잰다")
    void measure() throws IOException {
        // EVAL_SET: dev(고칠 때 쓰는 사례, 기본) | holdout(마지막 확인용, 손대지 않음) | fresh(11/11 지영 "새 가짜 데이터로 시험")
        //   | team(11/11 지영이 쓴 학생 글) | team2(11/11 팀원 문제지 글, 정답은 지영)
        //   | excluded(실제로 안 생기거나 사진을 봐야 정할 수 있어 점수에서 뺀 것) | all. 쉼표로 여러 개 (예: team,team2)
        String set = System.getenv().getOrDefault("EVAL_SET", "dev");
        List<String> sets = List.of(set.split(","));
        List<Sample> samples = load().stream().filter(s -> "all".equals(set) || sets.contains(s.set())).toList();
        Map<String, List<Row>> results = new LinkedHashMap<>();
        results.put(RuleWarningReviewer.NAME, run(new RuleWarningReviewer(TIDY), samples, 0));

        String key = System.getenv("GEMINI_API_KEY");
        String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.5-flash-lite");
        if (key != null && !key.isBlank()) {
            GeminiReviewerProperties props = new GeminiReviewerProperties(null, model, key, Duration.ofSeconds(20));
            // 무료 한도(분당 요청 수)를 넘지 않게 사이를 둔다
            results.put("gemini:" + model, run(new GeminiWarningReviewer(props, TIDY, new RuleWarningReviewer(TIDY)),
                    samples, 4500));
        }

        Path out = Path.of("target/eval/reviewer-eval.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report(samples, results, model));
        Files.writeString(Path.of("target/eval/reviewer-eval.json"), JSON.writeValueAsString(raw(results)));
        System.out.println(Files.readString(out));
        assertThat(results.get(RuleWarningReviewer.NAME)).hasSize(samples.size());
    }

    static List<Row> run(WarningReviewer reviewer, List<Sample> samples, long pauseMillis) {
        List<Row> rows = new ArrayList<>();
        for (Sample s : samples) {
            long start = System.nanoTime();
            Review r = reviewer.review(s.c());
            rows.add(new Row(s, r, (System.nanoTime() - start) / 1_000_000));
            if (pauseMillis > 0) {
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
        try (InputStream in = ReviewerEval.class.getResourceAsStream("/eval/appeal-cases.json")) {
            long n = 0;
            for (JsonNode j : JSON.readTree(in)) {
                List<String> evidence = new ArrayList<>();
                j.path("evidence").forEach(e -> evidence.add(e.asString()));
                List<String> items = new ArrayList<>();
                j.path("items").forEach(e -> items.add(e.asString()));
                List<Double> conf = new ArrayList<>();
                j.path("conf").forEach(e -> conf.add(e.asDouble()));
                String content = j.path("content").isNull() ? null : j.path("content").asString();
                Case c = new Case(++n, j.path("reason").asString(), j.path("source").asString(), j.path("type").asString(),
                        content, j.path("appeal").asString(), evidence, j.path("inPhoto").asBoolean(),
                        j.path("outPhoto").asBoolean(), items, conf);
                samples.add(new Sample(j.path("id").asString(), j.path("group").asString(), j.path("set").asString("dev"),
                        Decision.valueOf(j.path("truth").asString()), j.path("why").asString(), c));
            }
        }
        return samples;
    }

    /** 판마다 비교하려고 사례별 답을 그대로 남긴다 */
    static Map<String, Object> raw(Map<String, List<Row>> results) {
        Map<String, Object> all = new LinkedHashMap<>();
        results.forEach((name, rows) -> all.put(name, rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.s().id());
            m.put("truth", r.s().truth().name());
            m.put("decision", r.r().decision().name());
            m.put("confidence", r.r().confidence());
            m.put("applied", r.applied().name());
            m.put("reviewer", r.r().reviewer());
            m.put("millis", r.millis());
            m.put("reasons", r.r().reasons());
            return m;
        }).toList()));
        return all;
    }

    static String report(List<Sample> samples, Map<String, List<Row>> results, String model) {
        StringBuilder md = new StringBuilder();
        md.append("# 경고 철회 판단기 정확도 (사례 ").append(samples.size()).append("개)\n\n");
        md.append("| 판단기 | 맞춤 | 정확도 | 심각 오류(철회할 걸 유지) | 규칙으로 넘어감 | 평균 시간 | 최대 시간 |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<Row>> e : results.entrySet()) {
            List<Row> rows = e.getValue();
            long right = rows.stream().filter(Row::right).count();
            long serious = rows.stream().filter(Row::serious).count();
            long fellBack = e.getKey().startsWith("gemini")
                    ? rows.stream().filter(r -> RuleWarningReviewer.NAME.equals(r.r().reviewer())).count() : 0;
            double avg = rows.stream().mapToLong(Row::millis).average().orElse(0);
            long max = rows.stream().mapToLong(Row::millis).max().orElse(0);
            md.append("| %s | %d/%d | %.0f%% | %d | %s | %.0fms | %dms |\n".formatted(e.getKey(), right, rows.size(),
                    100.0 * right / rows.size(), serious, e.getKey().startsWith("gemini") ? fellBack + "번" : "-", avg, max));
        }
        if (!results.containsKey("gemini:" + model)) {
            md.append("\nGemini는 GEMINI_API_KEY가 없어 재지 않았어요.\n");
        }

        md.append("\n## 묶음별 맞춤\n\n| 묶음 | 사례 수 |");
        results.keySet().forEach(k -> md.append(' ').append(k).append(" |"));
        md.append("\n|---|---|");
        results.keySet().forEach(k -> md.append("---|"));
        md.append('\n');
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < samples.size(); i++) {
            groups.computeIfAbsent(samples.get(i).group(), g -> new ArrayList<>()).add(i);
        }
        groups.forEach((g, idx) -> {
            md.append("| ").append(g).append(" | ").append(idx.size()).append(" |");
            results.values().forEach(rows -> md.append(' ')
                    .append(idx.stream().filter(i -> rows.get(i).right()).count()).append('/').append(idx.size()).append(" |"));
            md.append('\n');
        });

        md.append("\n## 틀린 사례\n\n| 판단기 | 사례 | 묶음 | 정답 | 적용(판단기 답) | 확신도 | 정답 근거 |\n|---|---|---|---|---|---|---|\n");
        results.forEach((name, rows) -> rows.stream().filter(r -> !r.right()).forEach(r -> md.append(
                "| %s | %s | %s | %s | %s(%s) | %.2f | %s |\n".formatted(name, r.s().id(), r.s().group(), r.s().truth(),
                        r.applied(), r.r().decision(), r.r().confidence(), r.s().why()))));
        return md.toString();
    }
}
