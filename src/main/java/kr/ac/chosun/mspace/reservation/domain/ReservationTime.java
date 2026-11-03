package kr.ac.chosun.mspace.reservation.domain;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;

/**
 * 미리 예약의 시작·끝 시각 (값 객체). {@link #of}로만 만들고, 만들 때 시각 규칙을 모두 확인한다.
 * 그래서 이 타입을 받은 코드는 "규칙에 맞는 시각"이라고 믿고 쓸 수 있다.
 * 스프링·DB 없이 단위 테스트한다 (ReservationTimeTest).
 * <p>
 * 즉시 시작(FR-RSV-103)은 시작이 30분 칸이 아니고 길이 규칙도 달라서 {@link #instant}로 따로 만든다.
 *
 * @param start 시작 시각 (포함)
 * @param end   끝 시각 (포함 안 함)
 */
public record ReservationTime(Instant start, Instant end) {

    /**
     * 규칙: 30분 칸에 맞는지 → 길이가 0보다 크고 최대(2시간) 이하인지 → 지난 시각이 아닌지 → 시작이 7일 안인지.
     * 숫자는 {@link ReservationPolicy} 설정값을 쓴다.
     *
     * @param now 지금 시각 (테스트에서 고정할 수 있게 밖에서 받는다)
     * @throws BusinessException 규칙 하나라도 틀리면 400 (어느 규칙인지는 오류 코드로 구분)
     */
    public static ReservationTime of(Instant start, Instant end, ReservationPolicy policy, Instant now) {
        long slotSeconds = policy.slotMinutes() * 60L;
        if (!onSlot(start, slotSeconds) || !onSlot(end, slotSeconds)) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ON_SLOT);
        }
        Duration length = Duration.between(start, end);
        if (length.isNegative() || length.isZero() || length.toMinutes() > policy.maxMinutes()) {
            throw new BusinessException(ErrorCode.RESERVATION_INVALID_LENGTH);
        }
        if (!start.isAfter(now)) {
            throw new BusinessException(ErrorCode.RESERVATION_IN_PAST);
        }
        // 시작 시각만 본다: 7일째 23:00–다음 날 01:00은 된다 (자정 넘김 허용)
        LocalDate today = LocalDate.ofInstant(now, SEOUL);
        if (!LocalDate.ofInstant(start, SEOUL).isBefore(today.plusDays(policy.bookingWindowDays()))) {
            throw new BusinessException(ErrorCode.RESERVATION_OUT_OF_RANGE);
        }
        return new ReservationTime(start, end);
    }

    /**
     * 즉시 시작 (FR-RSV-103). 시작 = 버튼 누른 시각(초 아래는 버림), 끝 = 사용자가 고른 30분 칸.
     * 끝은 지금보다 뒤이고, (지금 + 최대 2시간)을 30분 칸으로 올린 시각 이하여야 한다 (14:17 → 최대 16:30).
     * 다음 예약 전까지인지는 DB 겹침 제약(no_room_overlap)이 409로 막는다.
     *
     * @throws BusinessException 끝이 30분 칸이 아니면 400(RESERVATION_NOT_ON_SLOT), 길이가 틀리면 400(RESERVATION_INVALID_LENGTH)
     */
    public static ReservationTime instant(Instant now, Instant end, ReservationPolicy policy) {
        long slotSeconds = policy.slotMinutes() * 60L;
        if (!onSlot(end, slotSeconds)) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ON_SLOT);
        }
        Instant start = now.truncatedTo(ChronoUnit.SECONDS);
        Instant latest = ceilToSlot(start.plus(Duration.ofMinutes(policy.maxMinutes())), slotSeconds);
        if (!end.isAfter(start) || end.isAfter(latest)) {
            throw new BusinessException(ErrorCode.RESERVATION_INVALID_LENGTH,
                    "지금 바로 쓰기는 지금부터 %s까지 고를 수 있습니다".formatted(latest.atZone(SEOUL).toLocalTime()));
        }
        return new ReservationTime(start, end);
    }

    private static Instant ceilToSlot(Instant t, long slotSeconds) {
        long s = t.getEpochSecond();
        long rem = Math.floorMod(s, slotSeconds);
        return rem == 0 && t.getNano() == 0 ? t : Instant.ofEpochSecond(s - rem + slotSeconds);
    }

    /** 서울은 UTC+9라 UTC로 30분 단위면 서울로도 30분 단위 */
    private static boolean onSlot(Instant t, long slotSeconds) {
        return t.getNano() == 0 && t.getEpochSecond() % slotSeconds == 0;
    }
}
