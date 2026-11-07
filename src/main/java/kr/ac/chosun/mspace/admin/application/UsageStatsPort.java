package kr.ac.chosun.mspace.admin.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 관리자 통계 저장소. 구현: JdbcUsageStatsAdapter. 기간은 [from, to), cap = min(to, 지금) */
public interface UsageStatsPort {

    boolean isAdmin(long userId);

    /** 방마다 한 줄 (예약이 없는 방도). 예약은 시작 시각이 기간 안인 것 */
    List<RoomRow> rooms(Instant from, Instant to, Instant cap);

    /** 실제 이용(입실)한 사람 수. 키 = 방 id, 0 = 모든 방 */
    Map<Integer, Long> uniqueUsers(Instant from, Instant to);

    /** 서울 시각 0–23시별 예약·이용 수 (시작 시각 기준) */
    List<HourRow> byHour(Instant from, Instant to);

    /** 방별 센서 값. 키 = 방 id */
    Map<Integer, SensorRow> sensors(Instant from, Instant cap, int thresholdPpm);

    /** 방별 알림 효과. 키 = 방 id (모든 방이 들어 있다) */
    Map<Integer, AlertRow> alerts(Instant from, Instant to, Instant cap, int thresholdPpm, int resolvedWithinMinutes);

    /**
     * @param noShowWarned       노쇼 직전 알림(FR-NTF-104)을 받은 예약 중 결과가 난 것 (입실했거나 입실 마감이 지남). 시작 시각이 기간 안
     * @param noShowCheckedIn    그중 입실한 예약
     * @param ventilationChecked 환기 알림(FR-DEV-104) 중 확인 시간이 다 지난 것
     * @param ventilationResolved 그중 확인 시간 안에 CO2가 기준 이하로 내려간 것
     */
    record AlertRow(long noShowWarned, long noShowCheckedIn, long ventilationChecked, long ventilationResolved) {
        static final AlertRow EMPTY = new AlertRow(0, 0, 0, 0);
    }

    record RoomRow(int roomId, String roomName, long total, long canceled, long pending, long noShow, long used,
                   long forced, long awayReleased, long autoCheckedOut, long bookedMinutes, long usedMinutes,
                   long shortUses) {
    }

    record HourRow(int hour, long reservations, long used) {
    }

    /**
     * @param co2Sum                         평균을 모든 방으로 합치려고 합을 그대로 준다
     * @param occupiedMinutes                센서가 "있음"이라고 본 시간
     * @param occupiedWithoutReservationMinutes 그중 입실한 예약이 없던 시간 (예약 없이 쓴 것 같은 시간)
     */
    record SensorRow(long co2Readings, long co2Sum, Integer co2Max, long co2Over, long ventilationAlerts,
                     long occupiedMinutes, long occupiedWithoutReservationMinutes) {
        static final SensorRow EMPTY = new SensorRow(0, 0, null, 0, 0, 0, 0);
    }
}
