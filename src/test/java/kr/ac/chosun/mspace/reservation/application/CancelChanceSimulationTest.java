package kr.ac.chosun.mspace.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import kr.ac.chosun.mspace.common.config.RelayChancePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 진짜 기록이 아직 없어서 모의 데이터로 공식이 쓸 만한지 잰다 (11/9).
 * 숨은 규칙: 요일·시간 칸마다 취소 경향이 다르고(5–45%), 미리 잡을수록 더 취소한다. 8주로 배우고 9주째를 맞힌다.
 * 비교 대상은 "늘 전체 평균 취소율을 말하는" 방법. 점수는 Brier(예측 확률과 실제 0/1의 차이 제곱 평균, 작을수록 좋음).
 */
class CancelChanceSimulationTest {

    static final RelayChancePolicy POLICY = new RelayChancePolicy(8, 30, 0.15, 3, 1.3, 24, 0.6, 0.9);
    static final int ROOMS = 5;
    static final int WEEKS = 8;

    /** 모의 예약 한 건: 요일(1–7), 시(9–21), 미리 잡은 시간, 30분 전까지 취소됐는지 */
    record Booking(int dow, int hour, Duration lead, boolean canceled) {
    }

    /** 숨은 규칙으로 한 주치 예약을 만든다. 칸마다 방 5개 중 몇 개가 찬다 */
    static List<Booking> week(SplittableRandom rnd, Map<Integer, Double> slotRate, boolean leadMatters) {
        List<Booking> out = new ArrayList<>();
        for (int dow = 1; dow <= 7; dow++) {
            for (int hour = 9; hour <= 21; hour++) {
                double busy = dow <= 5 && hour >= 13 && hour <= 19 ? 0.9 : 0.4;
                for (int room = 0; room < ROOMS; room++) {
                    if (rnd.nextDouble() >= busy) {
                        continue;
                    }
                    Duration lead = Duration.ofHours(1 + rnd.nextInt(24 * 7));
                    double p = slotRate.get(dow * 100 + hour);
                    if (leadMatters) {
                        p *= lead.toDays() >= 3 ? 1.5 : lead.toHours() < 24 ? 0.4 : 1.0;
                    }
                    out.add(new Booking(dow, hour, lead, rnd.nextDouble() < Math.min(0.95, p)));
                }
            }
        }
        return out;
    }

    /** 시드 하나로 8주 배우고 9주째 맞히기. [공식 Brier, 평균값 Brier, 9주째 건수] */
    static double[] run(long seed, boolean slotsDiffer, boolean leadMatters) {
        SplittableRandom rnd = new SplittableRandom(seed);
        Map<Integer, Double> slotRate = new HashMap<>();
        for (int dow = 1; dow <= 7; dow++) {
            for (int hour = 9; hour <= 21; hour++) {
                slotRate.put(dow * 100 + hour, slotsDiffer ? 0.05 + 0.40 * rnd.nextDouble() : 0.2);
            }
        }
        List<Booking> history = new ArrayList<>();
        for (int w = 0; w < WEEKS; w++) {
            history.addAll(week(rnd, slotRate, leadMatters));
        }
        Map<Integer, long[]> counts = new HashMap<>();
        long allCanceled = 0;
        for (Booking b : history) {
            long[] c = counts.computeIfAbsent(b.dow() * 100 + b.hour(), k -> new long[2]);
            c[1]++;
            if (b.canceled()) {
                c[0]++;
                allCanceled++;
            }
        }
        double average = (double) allCanceled / history.size();

        List<Booking> next = week(rnd, slotRate, leadMatters);
        double formula = 0;
        double baseline = 0;
        for (Booking b : next) {
            long[] c = counts.getOrDefault(b.dow() * 100 + b.hour(), new long[2]);
            double p = CancelChance.estimate(c[0], c[1], b.lead(), POLICY).rate();
            double y = b.canceled() ? 1 : 0;
            formula += (p - y) * (p - y);
            baseline += (average - y) * (average - y);
        }
        return new double[] {formula / next.size(), baseline / next.size(), next.size()};
    }

    /** 시드 20개 평균 */
    static double[] average(boolean slotsDiffer, boolean leadMatters) {
        double f = 0;
        double b = 0;
        int better = 0;
        for (long seed = 1; seed <= 20; seed++) {
            double[] r = run(seed, slotsDiffer, leadMatters);
            f += r[0];
            b += r[1];
            if (r[0] < r[1]) {
                better++;
            }
        }
        return new double[] {f / 20, b / 20, better};
    }

    @Test
    @DisplayName("칸마다 취소 경향이 다르고 미리 잡을수록 더 취소하면, 공식이 평균값보다 잘 맞힌다 (시드 20개 평균)")
    void beatsAverageWhenPatternsExist() {
        double[] r = average(true, true);
        System.out.printf("[취소 가능성 모의] 공식 Brier %.4f, 평균값 Brier %.4f, 개선 %.1f%%, 이긴 시드 %d/20%n",
                r[0], r[1], (1 - r[0] / r[1]) * 100, (int) r[2]);
        assertThat(r[0]).isLessThan(r[1]);
    }

    @Test
    @DisplayName("대조: 경향이 어디나 같으면 공식이 평균값보다 크게 나빠지지 않는다 (Brier 차이 0.01 안)")
    void noPatternNoHarm() {
        double[] r = average(false, false);
        System.out.printf("[취소 가능성 모의·대조] 공식 Brier %.4f, 평균값 Brier %.4f%n", r[0], r[1]);
        assertThat(r[0] - r[1]).isLessThan(0.01);
    }
}
