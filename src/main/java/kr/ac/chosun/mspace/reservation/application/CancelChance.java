package kr.ac.chosun.mspace.reservation.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.common.config.RelayChancePolicy;

/**
 * 예약이 "시작 30분 전까지 취소될" 가능성. 모델이 아니라 정해진 공식이라 같은 입력이면 늘 같은 답이고 이유를 설명할 수 있다.
 * <pre>
 * b = 같은 요일·같은 시작 시(時) 미리 예약 중 30분 전까지 취소된 비율 (기록이 minSamples보다 적으면 startRate)
 * L = 미리 잡은 정도: earlyDays 이상 earlyFactor · lateHours 안 lateFactor · 그 사이 1
 * 가능성 = min(maxRate, b × L)
 * </pre>
 * 예약한 사람의 지난 취소 기록은 쓰지 않는다 (안건 B안: 개인 정보 없이).
 */
public final class CancelChance {

    /** 이 시간대는 취소가 시작값보다 잦은 편 */
    public static final String SLOT_OFTEN_CANCELED = "SLOT_OFTEN_CANCELED";
    /** 며칠 전에 미리 잡힌 예약 */
    public static final String BOOKED_EARLY = "BOOKED_EARLY";
    /** 시작 직전에 잡힌 예약 */
    public static final String BOOKED_LATE = "BOOKED_LATE";
    /** 같은 시간대 기록이 적어 시작값으로 계산 */
    public static final String FEW_RECORDS = "FEW_RECORDS";

    private CancelChance() {
    }

    /** 가능성(0–1)과 이유 코드 */
    public record Estimate(double rate, List<String> reasons) {

        /** 화면에 보여 줄 퍼센트 (반올림) */
        public int percent() {
            return (int) Math.round(rate * 100);
        }
    }

    /**
     * @param canceled 같은 요일·시간대에서 시작 30분 전까지 취소된 수
     * @param total    같은 요일·시간대의 끝난(결과가 난) 미리 예약 수
     * @param lead     그 예약을 잡은 때부터 시작까지
     */
    public static Estimate estimate(long canceled, long total, Duration lead, RelayChancePolicy p) {
        List<String> reasons = new ArrayList<>();
        double base;
        if (total < p.minSamples()) {
            base = p.startRate();
            reasons.add(FEW_RECORDS);
        } else {
            base = (double) canceled / total;
            if (base > p.startRate()) {
                reasons.add(SLOT_OFTEN_CANCELED);
            }
        }
        double factor = 1;
        if (lead.compareTo(Duration.ofDays(p.earlyDays())) >= 0) {
            factor = p.earlyFactor();
            reasons.add(BOOKED_EARLY);
        } else if (lead.compareTo(Duration.ofHours(p.lateHours())) < 0) {
            factor = p.lateFactor();
            reasons.add(BOOKED_LATE);
        }
        return new Estimate(Math.min(p.maxRate(), base * factor), List.copyOf(reasons));
    }
}
