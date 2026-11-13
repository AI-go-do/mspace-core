package kr.ac.chosun.mspace.ops;

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

/**
 * AI 자동 처리 규칙을 잴 가짜 사례 (11/12 지영). 실제 센서 기록이 아직 없어서 방·사람·센서를 흉내 낸다.
 * 사례마다 "실제로 무슨 일이 있었나"(정답)는 숨은 값으로 정하고, 규칙은 센서가 본 값(잡음·끊김·튀는 값 포함)만 본다.
 * 같은 씨앗이면 늘 같은 사례가 나온다 (연습 = 씨앗 1, 처음 보는 것 = 씨앗 2). 이름·학번은 없다.
 * 한계: 사례 모양(사람 수·잡음·끊김 길이)은 Claude가 정한 가짜라 실제 센서 기록으로 다시 재야 한다.
 */
final class OpsCases {

    static final int N = 300;
    static final Instant T0 = Instant.parse("2026-11-12T05:00:00Z");

    private OpsCases() {
    }

    /** @param fires 이 설정으로 규칙이 알리나(기록하나) · tag 틀린 사례를 볼 때 쓰는 설명 */
    record Sample(boolean truth, Predicate<OpsPolicy> fires, String tag) {
        Sample(boolean truth, Predicate<OpsPolicy> fires) {
            this(truth, fires, "");
        }
    }

    /** 규칙 이름 → 사례 만들기 */
    static Function<Random, List<Sample>> cases(String rule) {
        return switch (rule) {
            case "미리 환기" -> OpsCases::ventCases;
            case "예약 전 더움" -> OpsCases::hotCases;
            case "센서 고장" -> OpsCases::sensorCases;
            case "시간 넘김" -> OpsCases::overtimeCases;
            default -> OpsCases::unbookedCases;
        };
    }

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
            out.add(new Sample(truth, p -> OpsRules.roomHot(seen, T0, T0.plus(p.hot().before()), p.hot()),
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
}
