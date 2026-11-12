package kr.ac.chosun.mspace.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import kr.ac.chosun.mspace.common.config.OpsPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 자동 처리 숫자 규칙 정확도 측정 (11/12 지영). 가짜 사례는 {@link OpsCases}.
 * <ul>
 *   <li>연습(dev, 씨앗 1)으로 규칙을 고치고, 처음 보는 것(holdout, 씨앗 2)은 고칠 때 보지 않는다</li>
 *   <li>판마다 규칙 하나를 한 가지만 바꾼다. 판 목록은 {@link #ROUNDS}</li>
 *   <li>한계: 사례 모양은 Claude가 정한 가짜다. 재웅에게 실제 센서 기록(CSV)을 받으면 다시 잰다</li>
 * </ul>
 * 이름이 *Test가 아니라 평소 빌드에서는 돌지 않는다. 돌리는 법:
 * <pre>./mvnw -o test -Dtest=OpsRulesEval -Dsurefire.failIfNoSpecifiedTests=false</pre>
 * 결과: target/eval/ops-rules-eval.md
 */
class OpsRulesEval {


    record Round(String rule, String name, String change, OpsPolicy policy) {
    }

    static OpsPolicy.VentSoon vent(int minPoints, int floor, boolean fit) {
        return vent(minPoints, floor, fit, 5);
    }

    static OpsPolicy.VentSoon vent(int minPoints, int floor, boolean fit, int windowMin) {
        return vent(minPoints, floor, fit, windowMin, 10);
    }

    static OpsPolicy.VentSoon vent(int minPoints, int floor, boolean fit, int windowMin, int lookaheadMin) {
        return vent(minPoints, floor, fit, windowMin, lookaheadMin, false);
    }

    static OpsPolicy.VentSoon vent(int minPoints, int floor, boolean fit, int windowMin, int lookaheadMin,
                                   boolean smooth) {
        return new OpsPolicy.VentSoon(1000, Duration.ofMinutes(lookaheadMin), Duration.ofMinutes(windowMin), minPoints,
                floor, fit, smooth, 0, null, 0);
    }

    static OpsPolicy.Hot hot(int median, Duration fresh, boolean trend) {
        return hot(median, fresh, trend, false);
    }

    static OpsPolicy.Hot hot(int median, Duration fresh, boolean trend, boolean robust) {
        return hot(median, fresh, trend, robust, false);
    }

    static OpsPolicy.Hot hot(int median, Duration fresh, boolean trend, boolean robust, boolean sinceLast) {
        return new OpsPolicy.Hot(28, Duration.ofMinutes(10), median, fresh, trend, robust, sinceLast);
    }

    static OpsPolicy.Overtime over(boolean continuous, boolean atEnd, int nextGraceMin) {
        return new OpsPolicy.Overtime(Duration.ofMinutes(5), Duration.ofMinutes(15), continuous, atEnd,
                Duration.ofMinutes(nextGraceMin));
    }

    static OpsPolicy.Unbooked unb(int after, int early, int late) {
        return new OpsPolicy.Unbooked(Duration.ofMinutes(after), Duration.ofMinutes(early), Duration.ofMinutes(late));
    }

    static final Duration FOREVER = Duration.ofDays(365);
    static final OpsPolicy BASE = new OpsPolicy(Duration.ofMinutes(1), vent(2, 0, false), hot(1, FOREVER, false),
            new OpsPolicy.SensorDown(Duration.ofSeconds(60), false), over(false, false, 0), unb(0, 0, 0));

    static final OpsPolicy V1 = with(BASE, vent(2, 0, true));
    static final OpsPolicy H1 = with(BASE, hot(3, FOREVER, false));
    static final OpsPolicy S1 = with(BASE, new OpsPolicy.SensorDown(Duration.ofMinutes(3), false));
    static final OpsPolicy O1 = with(BASE, over(true, false, 0));
    static final OpsPolicy U1 = with(BASE, unb(5, 0, 0));

    static final OpsPolicy V2 = with(V1, vent(2, 800, true));
    static final OpsPolicy H2 = with(H1, hot(3, Duration.ofMinutes(5), false));
    static final OpsPolicy S2 = with(S1, new OpsPolicy.SensorDown(Duration.ofMinutes(3), true));
    static final OpsPolicy O2 = with(O1, over(true, false, 10));
    static final OpsPolicy U2 = with(U1, unb(5, 10, 0));
    static final OpsPolicy U3 = with(U1, unb(5, 10, 15));

    static final OpsPolicy V3 = with(V2, vent(4, 800, true));
    static final OpsPolicy H3 = with(H2, hot(3, Duration.ofMinutes(5), true));
    static final OpsPolicy H4 = with(H3, hot(3, Duration.ofMinutes(15), true));
    static final OpsPolicy S3 = with(S2, new OpsPolicy.SensorDown(Duration.ofMinutes(5), true));
    static final OpsPolicy O3 = with(O2, over(true, true, 10));
    static final OpsPolicy U4 = with(U3, unb(5, 10, 30));
    static final OpsPolicy U5 = with(U4, unb(10, 10, 30));

    static final OpsPolicy H5 = with(H4, hot(3, Duration.ofMinutes(15), true, true));
    static final OpsPolicy O4 = with(O3, over(true, true, 0));

    static final OpsPolicy V4 = with(V2, vent(2, 800, true, 10));

    static final OpsPolicy V5 = with(V2, vent(2, 800, true, 5, 8));
    static final OpsPolicy H6 = with(H5, hot(3, Duration.ofMinutes(15), true, true, true));
    static final OpsPolicy H7 = with(H6, hot(3, Duration.ofMinutes(60), true, true, true));

    static final OpsPolicy V6 = with(V5, vent(2, 800, true, 5, 8, true));
    static final OpsPolicy V7 = with(V6, vent(2, 750, true, 5, 8, true));
    static OpsPolicy.VentSoon near(int nearPpm, int nearMin, double fastPerMin) {
        return new OpsPolicy.VentSoon(1000, Duration.ofMinutes(8), Duration.ofMinutes(5), 2, 800, true, true, nearPpm,
                Duration.ofMinutes(nearMin), fastPerMin);
    }

    static final OpsPolicy V8 = with(V6, near(960, 30, 0));
    static final OpsPolicy V9 = with(V8, near(960, 30, 10));
    static final OpsPolicy V10 = with(V8, near(960, 30, 15));
    static final OpsPolicy V11 = with(V8, near(960, 30, 20));

    static OpsPolicy with(OpsPolicy p, Object part) {
        return new OpsPolicy(p.interval(),
                part instanceof OpsPolicy.VentSoon v ? v : p.ventSoon(),
                part instanceof OpsPolicy.Hot h ? h : p.hot(),
                part instanceof OpsPolicy.SensorDown s ? s : p.sensorDown(),
                part instanceof OpsPolicy.Overtime o ? o : p.overtime(),
                part instanceof OpsPolicy.Unbooked u ? u : p.unbooked());
    }

    /** 판 목록. 같은 규칙 안에서 앞 판에 한 가지만 더 바꾼다 */
    static final List<Round> ROUNDS = List.of(
            new Round("미리 환기", "0판", "기준: 최근 5분 첫 값·끝 값 두 개로 오르는 속도 → 10분 뒤 1000 넘으면", BASE),
            new Round("예약 전 더움", "0판", "기준: 가장 최근 온도 하나가 28도 이상이면", BASE),
            new Round("센서 고장", "0판", "기준: 신호가 60초 안 오면", BASE),
            new Round("시간 넘김", "0판", "기준: 끝나고 5~15분 사이에 \"있음\"이면", BASE),
            new Round("예약 없는 사용", "0판", "기준: 예약 시간이 아닌데 \"있음\"이면 바로", BASE),
            new Round("미리 환기", "1판", "두 값 대신 5분 안 모든 값으로 기울기(최소제곱)", V1),
            new Round("예약 전 더움", "1판", "최근 값 하나 대신 3개의 가운데 값", H1),
            new Round("센서 고장", "1판", "60초 → 3분", S1),
            new Round("시간 넘김", "1판", "끝나기 전부터 계속 \"있음\"일 때만", O1),
            new Round("예약 없는 사용", "1판", "\"있음\"이 5분 이어져야", U1),
            new Round("미리 환기", "2판", "지금 값이 800 아래면 안 봄", V2),
            new Round("예약 전 더움", "2판", "5분보다 오래된 값은 안 믿음", H2),
            new Round("센서 고장", "2판", "모든 방이 같이 끊기면 네트워크 문제로 보고 안 알림", S2),
            new Round("시간 넘김", "2판", "다음 예약이 10분 안에 시작하면 안 알림", O2),
            new Round("예약 없는 사용", "2판", "예약 시작 10분 전부터는 그 팀으로 봄", U2),
            new Round("예약 없는 사용", "3판", "예약 끝나고 15분까지는 그 팀으로 봄", U3),
            new Round("미리 환기", "3판", "5분 안 값이 4개는 있어야 봄", V3),
            new Round("예약 전 더움", "3판", "기울기로 시작 시각 온도를 예측", H3),
            new Round("예약 전 더움", "4판", "믿는 값 5분 → 15분", H4),
            new Round("센서 고장", "3판", "3분 → 5분", S3),
            new Round("시간 넘김", "3판", "\"계속 있음\" 대신 \"끝날 때 있음 + 지금 있음\"", O3),
            new Round("예약 없는 사용", "4판", "예약 끝나고 15분 → 30분까지 그 팀으로 봄", U4),
            new Round("예약 없는 사용", "5판", "\"있음\"이 5분 → 10분 이어져야", U5),
            new Round("예약 전 더움", "5판", "기울기를 튀는 값에 강하게: 최근 3개·오래된 3개의 가운데 값으로", H5),
            new Round("시간 넘김", "4판", "다음 예약 10분 안이어도 알림 (3판에서 놓친 13개가 모두 다음 팀이 먼저 온 때)", O4),
            new Round("미리 환기", "4판", "기울기를 재는 값 5분 → 10분 (2판 기준. 헛알림 23개 중 17개가 창문·나감 없이 잡음 탓)", V4),
            new Round("미리 환기", "5판", "예측 10분 → 8분 (2판 기준, 잡음으로 넘을 것 같다고 보는 것 줄이기)", V5),
            new Round("예약 전 더움", "6판", "마지막 값이 오래됐으면 그 시각부터 시작까지 예측 (5판 기준)", H6),
            new Round("예약 전 더움", "7판", "믿는 값 15분 → 60분 (5판에서 놓친 것 대부분이 센서 멈춘 방)", H7),
            new Round("미리 환기", "6판", "지금 값을 최근 3개의 가운데 값으로 (5판 기준. 헛알림 중 마지막 값이 튄 것)", V6),
            new Round("미리 환기", "7판", "800 → 750 아래면 안 봄 (6판 기준. 놓친 것 중 빨리 오르는데 800 아래)", V7),
            new Round("미리 환기", "8판", "960 넘으면 30분 예측 (6판 기준)", V8),
            new Round("미리 환기", "9판", "960 아래는 분당 10 넘게 오를 때만 (8판 기준)", V9),
            new Round("미리 환기", "10판", "분당 15", V10),
            new Round("미리 환기", "11판", "분당 20", V11));

    // ───────────── 재기 ─────────────

    record Score(int right, int total, int falseAlarm, int missed, int positives) {
        String pct() {
            return "%.1f%%".formatted(100.0 * right / total);
        }
    }

    static Score score(List<OpsCases.Sample> xs, OpsPolicy p) {
        int right = 0;
        int fa = 0;
        int miss = 0;
        int pos = 0;
        for (OpsCases.Sample s : xs) {
            boolean f = s.fires().test(p);
            pos += s.truth() ? 1 : 0;
            if (f == s.truth()) {
                right++;
            } else if (f) {
                fa++;
            } else {
                miss++;
            }
        }
        return new Score(right, xs.size(), fa, miss, pos);
    }

    @Test
    void debug() {
        String which = System.getenv("OPS_DEBUG");
        if (which == null) {
            return;
        }
        Round round = ROUNDS.get(Integer.parseInt(which));
        for (OpsCases.Sample s : OpsCases.cases(round.rule()).apply(new Random(1))) {
            boolean f = s.fires().test(round.policy());
            if (f != s.truth()) {
                System.out.println((f ? "FA " : "MISS ") + s.tag());
            }
        }
    }

    /** 고른 판 (application.yml mspace.ops와 같음). 같은 점수면 잘못 알림이 적은 쪽, 1개 차이면 단순한 쪽 */
    static final java.util.Map<String, String> ADOPTED = java.util.Map.of("미리 환기", "6판", "예약 전 더움", "7판",
            "센서 고장", "3판", "시간 넘김", "2판", "예약 없는 사용", "5판");

    @Test
    @DisplayName("자동 처리 규칙 5개를 가짜 사례로 잰다")
    void measure() throws IOException {
        StringBuilder md = new StringBuilder();
        for (String rule : List.of("미리 환기", "예약 전 더움", "센서 고장", "시간 넘김", "예약 없는 사용")) {
            List<String> rows = new ArrayList<>();
            double best = -1;
            for (Round round : ROUNDS.stream().filter(r -> r.rule().equals(rule)).toList()) {
                Score dev = score(OpsCases.cases(rule).apply(new Random(1)), round.policy());
                Score hold = score(OpsCases.cases(rule).apply(new Random(2)), round.policy());
                double d = 100.0 * dev.right() / dev.total();
                String verdict = round.name().equals("0판") ? "기준" : round.name().equals(ADOPTED.get(rule)) ? "**채택**"
                        : d < best ? "탈락" : "비교";
                best = Math.max(best, d);
                rows.addFirst("| %s | %s | %s | %s | %d · %d | %s |".formatted(round.name(), verdict, dev.pct(),
                        hold.pct(), hold.falseAlarm(), hold.missed(), round.change()));
                System.out.printf("%d %s dev %s (FA %d, miss %d, pos %d) holdout %s (FA %d, miss %d, pos %d)%n",
                        ROUNDS.indexOf(round), round.name().replace("판", ""), dev.pct(), dev.falseAlarm(), dev.missed(),
                        dev.positives(), hold.pct(), hold.falseAlarm(), hold.missed(), hold.positives());
            }
            md.append("#### ").append(rule).append("\n\n| 판 | 판단 | 연습 300 | 처음 보는 것 300 | 잘못 알림 · 놓침 (처음 보는 것) | 무엇을 바꿨나 |\n|---|---|---|---|---|---|\n");
            rows.forEach(r -> md.append(r).append("\n"));
            md.append("\n");
        }
        Path out = Path.of("target/eval/ops-rules-eval.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        assertThat(md).isNotEmpty();
    }
}
