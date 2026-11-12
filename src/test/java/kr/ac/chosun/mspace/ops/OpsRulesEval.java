package kr.ac.chosun.mspace.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;
import kr.ac.chosun.mspace.common.config.OpsPolicy;
import kr.ac.chosun.mspace.ops.domain.OpsRules;
import kr.ac.chosun.mspace.ops.domain.OpsRules.Point;
import kr.ac.chosun.mspace.ops.domain.OpsRules.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 자동 처리 숫자 규칙 정확도 측정 (11/12 지영). 실제 센서 기록이 아직 없어서, 방·사람·센서를 흉내 낸 가짜 사례를
 * 만들어 잰다. 사례마다 "실제로 무슨 일이 있었나"(정답)는 숨은 값으로 정하고, 규칙은 센서가 본 값(잡음·끊김·튀는 값 포함)만 본다.
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

    static final int N = 300;
    static final Instant T0 = Instant.parse("2026-11-12T05:00:00Z");

    record Sample(boolean truth, Predicate<OpsPolicy> fires, String tag) {
        Sample(boolean truth, Predicate<OpsPolicy> fires) {
            this(truth, fires, "");
        }
    }

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
                floor, fit, smooth);
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
            new Round("미리 환기", "7판", "800 → 750 아래면 안 봄 (6판 기준. 놓친 것 중 빨리 오르는데 800 아래)", V7));

    // ───────────── 가짜 사례 만들기 ─────────────

    static double gauss(Random r, double sd) {
        return r.nextGaussian() * sd;
    }

    static double u(Random r, double a, double b) {
        return a + r.nextDouble() * (b - a);
    }

    static Instant at(double minutes) {
        return T0.plusMillis(Math.round(minutes * 60_000));
    }

    /** 미리 환기: 지금(0분) 센서 값은 1000 이하. 정답 = 실제 CO2가 10분 안에 1000을 넘음 */
    static List<Sample> ventCases(Random r) {
        List<Sample> out = new ArrayList<>();
        while (out.size() < N) {
            int people = 1 + r.nextInt(8);
            double gen = people * u(r, 3, 6);               // 사람 수만큼 오름 (ppm/분)
            double k = u(r, 0.005, 0.03);                    // 문틈 환기
            double c = u(r, 450, 900);
            double noise = u(r, 5, 25);
            double windowAt = r.nextDouble() < 0.2 ? u(r, -4, 9) : 99;   // 창문 엶
            double leaveAt = r.nextDouble() < 0.1 ? u(r, -4, 9) : 99;    // 나감
            double[] truth = new double[81];                 // -10분 ~ +10분, 15초마다
            for (int i = 0; i < truth.length; i++) {
                double t = -10 + i * 0.25;
                truth[i] = c;
                double d = (t < leaveAt ? gen : 0) - k * (c - 420) - (t >= windowAt ? 0.15 * (c - 420) : 0);
                c += d * 0.25;
            }
            List<Point> seen = new ArrayList<>();
            for (int m = 0; m >= -10; m--) {
                if (m < 0 && r.nextDouble() < 0.05) {
                    continue;                                // 값 빠짐
                }
                double v = truth[(m + 10) * 4] + gauss(r, noise) + (r.nextDouble() < 0.05 ? u(r, 60, 150) : 0);
                seen.add(new Point(at(m), Math.round(v)));
            }
            if (seen.getFirst().value() > 1000 || truth[40] >= 1000) {
                continue;                                    // 이미 넘었으면 기존 환기 알림이 맡음
            }
            boolean will = false;
            for (int i = 41; i < truth.length; i++) {
                will |= truth[i] >= 1000;
            }
            out.add(new Sample(will, p -> OpsRules.ventSoon(seen, T0, p.ventSoon()),
                    "n=%d c0=%.0f seen=%.0f win=%.1f leave=%.1f noise=%.0f".formatted(people, truth[40],
                            seen.getFirst().value(), windowAt, leaveAt, noise)));
        }
        return out;
    }

    /** 예약 전 더움: 시작 10분 전(0분)에 본다. 정답 = 시작할 때 실제 온도 28도 이상 */
    static List<Sample> hotCases(Random r) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            double now = u(r, 24, 31.5);
            double trend = u(r, -0.2, 0.08);                 // 에어컨이면 내려감
            double sd = u(r, 0.2, 0.6);
            boolean truth = now + trend * 10 >= 28;
            double stale = r.nextDouble() < 0.15 ? u(r, 6, 60) : 0;   // 센서가 stale분 전부터 멈춤
            List<Point> seen = new ArrayList<>();
            for (int m = 0; m >= -10; m--) {
                double t = m - stale;
                double v = now + trend * t + gauss(r, sd) + (r.nextDouble() < 0.06 ? u(r, 2, 4) : 0);
                seen.add(new Point(at(t), Math.round(v * 10) / 10.0));
            }
            out.add(new Sample(truth, p -> OpsRules.roomHot(seen, T0, p.hot()),
                    (stale > 0 ? "stale " : "") + "now=%.1f trend=%.2f sd=%.1f".formatted(now, trend, sd)));
        }
        return out;
    }

    /**
     * 센서 고장: 방 5개, 10초마다 신호. 20분 동안 1분마다 본다. 정답 = 0번 방 장치가 정말 죽음.
     * 종류: 멀쩡 35% · 와이파이 잠깐 끊김 35% · 죽음 20% · 학교망·서버 끊김(모든 방) 10%
     */
    static List<Sample> sensorCases(Random r) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            double kind = r.nextDouble();
            double blipFrom = 1e9;
            double blipTo = 0;
            double deadFrom = 1e9;
            double allFrom = 1e9;
            double allTo = 0;
            if (kind < 0.35) {
                // 멀쩡
            } else if (kind < 0.70) {
                blipFrom = u(r, 60, 900);
                blipTo = blipFrom + u(r, 20, 240);
            } else if (kind < 0.90) {
                deadFrom = u(r, 60, 600);
            } else {
                allFrom = u(r, 60, 900);
                allTo = allFrom + u(r, 60, 480);
            }
            List<List<Instant>> sent = new ArrayList<>();
            for (int room = 0; room < 5; room++) {
                List<Instant> got = new ArrayList<>();
                for (double s = u(r, 0, 10); s <= 1200; s += 10 + gauss(r, 0.5)) {
                    boolean down = (s >= allFrom && s < allTo) || r.nextDouble() < 0.02
                            || (room == 0 && ((s >= blipFrom && s < blipTo) || s >= deadFrom));
                    if (!down) {
                        got.add(T0.plusMillis(Math.round(s * 1000)));
                    }
                }
                sent.add(got);
            }
            boolean truth = deadFrom < 1e9;
            out.add(new Sample(truth, p -> {
                for (int m = 1; m <= 20; m++) {
                    Instant now = T0.plusSeconds(m * 60L);
                    Instant mine = last(sent.get(0), now);
                    boolean other = false;
                    for (int room = 1; room < 5; room++) {
                        Instant o = last(sent.get(room), now);
                        other |= o != null && now.isBefore(o.plus(p.sensorDown().after()));
                    }
                    if (OpsRules.sensorDown(mine, now, other, p.sensorDown())) {
                        return true;
                    }
                }
                return false;
            }));
        }
        return out;
    }

    static Instant last(List<Instant> xs, Instant now) {
        Instant best = null;
        for (Instant x : xs) {
            if (!x.isAfter(now)) {
                best = x;
            }
        }
        return best;
    }

    /** 1분 칸 "있음" 기록에서 (지금 있음?, 있음이 시작된 시각) */
    record Presence(boolean occupied, Instant since) {
    }

    static Presence presence(boolean[] seen, int minute) {
        if (!seen[minute]) {
            return new Presence(false, null);
        }
        int s = minute;
        while (s > 0 && seen[s - 1]) {
            s--;
        }
        return new Presence(true, at(s - 60));
    }

    /**
     * 시간 넘김: 예약이 0분에 끝나 자동 퇴실. 0~15분 1분마다 본다. 정답 = 그 팀이 끝난 뒤 5분 넘게 남음.
     * 섞인 것: 미리 나감 · 짐 싸느라 0~4분 · 다음 팀 연달아 예약(먼저 들어옴) · 레이더 헛신호 · 가만히 앉아 잠깐 "없음"
     */
    static List<Sample> overtimeCases(Random r) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            double kind = r.nextDouble();
            double leave = kind < 0.4 ? -u(r, 1, 30) : kind < 0.6 ? u(r, 0, 4) : u(r, 6, 40);
            boolean truth = leave >= 5;
            Instant nextStart = null;
            double nextIn = 1e9;
            if (r.nextDouble() < 0.3) {
                int gap = new int[]{0, 0, 30, 60}[r.nextInt(4)];
                nextStart = at(gap);
                nextIn = gap - u(r, 0, 10);
            }
            double ghostFrom = r.nextDouble() < 0.1 ? u(r, 2, 10) : 1e9;
            double ghostTo = ghostFrom + u(r, 2, 8);
            double dropFrom = r.nextDouble() < 0.15 ? u(r, -30, 10) : 1e9;
            double dropTo = dropFrom + u(r, 1, 2);
            boolean[] seen = new boolean[76];               // -60분 ~ +15분
            for (int m = 0; m < seen.length; m++) {
                double t = m - 60;
                boolean someone = t < leave || t >= nextIn || (t >= ghostFrom && t < ghostTo);
                seen[m] = someone && !(t >= dropFrom && t < dropTo);
            }
            Instant next = nextStart;
            String tag = "leave=%.0f next=%s ghost=%.0f drop=%.0f".formatted(leave, nextIn < 1e8 ? "%.0f".formatted(nextIn) : "-",
                    ghostFrom < 1e8 ? ghostFrom : -99, dropFrom < 1e8 ? dropFrom : -99);
            out.add(new Sample(truth, p -> {
                for (int m = 0; m <= 15; m++) {
                    Presence pr = presence(seen, m + 60);
                    if (OpsRules.overtime(T0, seen[60], pr.occupied(), pr.since(), at(m), next, p.overtime())) {
                        return true;
                    }
                }
                return false;
            }, tag));
        }
        return out;
    }

    /**
     * 예약 없는 사용: 3시간(-60~120분) 기록, 0~120분 1분마다 본다. 정답 = 예약 없는 사람이 5분 넘게 있음.
     * 섞인 것: 예약 팀 일찍 옴(0~15분 전) · 예약 팀 늦게 나감(시간 넘김) · 잠깐 들여다봄(1~4분) · 레이더 헛신호 · 잠깐 "없음"
     */
    static List<Sample> unbookedCases(Random r) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            boolean[] someone = new boolean[181];
            List<Span> bookings = new ArrayList<>();
            int start = 30 + 30 * r.nextInt(3);              // 예약 하나: -30, 0, 30분 시작 (60분 앞당겨 씀)
            if (r.nextDouble() < 0.7) {
                int len = 30 * (1 + r.nextInt(4));
                double in = start - (r.nextDouble() < 0.8 ? u(r, 0, 10) : u(r, 10, 15));
                double outAt = start + len + (r.nextDouble() < 0.3 ? -u(r, 5, 25) : u(r, -5, 25));
                boolean button = outAt < start + len && r.nextDouble() < 0.5;
                bookings.add(new Span(at(start - 60), at((button ? outAt : start + len) - 60)));
                fill(someone, in, outAt);
            }
            boolean truth = false;
            double kind = r.nextDouble();
            if (kind < 0.35 || kind < 0.55) {
                double len = kind < 0.35 ? u(r, 5, 60) : u(r, 1, 4);
                double from = findFree(r, someone, bookings, len);
                if (from >= 0) {
                    fill(someone, from, from + len);
                    truth = len >= 5;
                }
            }
            if (r.nextDouble() < 0.15) {
                double from = u(r, 60, 170);
                fill(someone, from, from + u(r, 2, 10));
            }
            boolean[] seen = someone.clone();
            if (r.nextDouble() < 0.2) {
                int d = 60 + r.nextInt(120);
                seen[d] = false;
            }
            boolean answer = truth;
            out.add(new Sample(answer, p -> {
                for (int m = 60; m <= 180; m++) {
                    Presence pr = presence(seen, m);
                    if (OpsRules.unbooked(pr.occupied(), pr.since(), at(m - 60), bookings, p.unbooked())) {
                        return true;
                    }
                }
                return false;
            }));
        }
        return out;
    }

    static void fill(boolean[] xs, double from, double to) {
        for (int m = 0; m < xs.length; m++) {
            if (m >= from && m < to) {
                xs[m] = true;
            }
        }
    }

    /** 예약 시간과 그 앞뒤 20분, 이미 사람 있는 칸을 피한 빈 자리 시작 분. 없으면 -1 */
    static double findFree(Random r, boolean[] someone, List<Span> bookings, double len) {
        for (int tries = 0; tries < 50; tries++) {
            double from = u(r, 62, 175 - len);
            boolean ok = from + len <= 180;
            for (int m = (int) from; ok && m < from + len; m++) {
                ok = !someone[m];
            }
            for (Span b : bookings) {
                double bs = Duration.between(T0, b.start()).toSeconds() / 60.0 + 60;
                double be = Duration.between(T0, b.end()).toSeconds() / 60.0 + 60;
                ok &= from + len < bs - 20 || from > be + 20;
            }
            if (ok) {
                return from;
            }
        }
        return -1;
    }

    // ───────────── 재기 ─────────────

    record Score(int right, int total, int falseAlarm, int missed, int positives) {
        String pct() {
            return "%.1f%%".formatted(100.0 * right / total);
        }
    }

    static Score score(List<Sample> xs, OpsPolicy p) {
        int right = 0;
        int fa = 0;
        int miss = 0;
        int pos = 0;
        for (Sample s : xs) {
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

    static Function<Random, List<Sample>> cases(String rule) {
        return switch (rule) {
            case "미리 환기" -> OpsRulesEval::ventCases;
            case "예약 전 더움" -> OpsRulesEval::hotCases;
            case "센서 고장" -> OpsRulesEval::sensorCases;
            case "시간 넘김" -> OpsRulesEval::overtimeCases;
            default -> OpsRulesEval::unbookedCases;
        };
    }

    @Test
    void debug() {
        String which = System.getenv("OPS_DEBUG");
        if (which == null) {
            return;
        }
        Round round = ROUNDS.get(Integer.parseInt(which));
        for (Sample s : cases(round.rule()).apply(new Random(1))) {
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
                Score dev = score(cases(rule).apply(new Random(1)), round.policy());
                Score hold = score(cases(rule).apply(new Random(2)), round.policy());
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
