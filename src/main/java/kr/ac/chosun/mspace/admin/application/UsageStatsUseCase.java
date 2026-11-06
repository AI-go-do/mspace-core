package kr.ac.chosun.mspace.admin.application;

import java.time.LocalDate;
import java.util.List;

/** 관리자 이용 통계 (8차 회의 관리자 통계 + 센서). 구현: {@link UsageStatsService} */
public interface UsageStatsUseCase {

    /** 한 번에 볼 수 있는 기간 (날짜 수) */
    int MAX_DAYS = 92;

    /** from·to는 서울 날짜, to 포함. 빼면 오늘까지 7일 */
    UsageStats usage(long adminId, LocalDate from, LocalDate to);

    record UsageStats(LocalDate from, LocalDate to, int days, Usage total, List<Usage> rooms, List<Hour> byHour) {
    }

    /** roomId·roomName은 전체(total)일 때 null */
    record Usage(Integer roomId, String roomName, Reservations reservations, Rates rates, Minutes minutes,
                 long shortUses, long uniqueUsers, Sensor sensor) {
    }

    /**
     * @param total   시작 시각이 기간 안인 예약 (취소 포함)
     * @param pending 아직 시작 안 했거나 입실 기다리는 예약 (BOOKED)
     * @param used    입실한 예약 (사용 중 포함)
     * @param forced  그중 관리자 강제 퇴실
     */
    record Reservations(long total, long canceled, long pending, long noShow, long used, long forced,
                        long awayReleased, long autoCheckedOut) {
    }

    /**
     * 0–1 사이 소수 셋째 자리. 분모가 0이면 null.
     *
     * @param noShow      노쇼 / (취소·대기 뺀 예약)
     * @param actualUse   입실 / (취소·대기 뺀 예약)
     * @param utilization 실제 이용 시간 / (기간 중 지난 시간, 하루 24시간 기준)
     */
    record Rates(Double noShow, Double actualUse, Double utilization) {
    }

    /** booked = 취소 뺀 예약 시간 합, used = 입실 ~ 퇴실(사용 중이면 지금) 합, avgUsed = used / 입실 예약 수 */
    record Minutes(long booked, long used, Long avgUsed) {
    }

    /**
     * 센서 값. 재실은 센서가 상태가 바뀔 때만 기록하므로, 바뀐 기록 사이를 그 상태로 본 근삿값이다.
     *
     * @param co2OverRate 기준(thresholdPpm)을 넘은 측정의 비율
     */
    record Sensor(long co2Readings, Integer co2AvgPpm, Integer co2MaxPpm, Double co2OverRate, int thresholdPpm,
                  long ventilationAlerts, long occupiedMinutes, long occupiedWithoutReservationMinutes) {
    }

    record Hour(int hour, long reservations, long used) {
    }
}
