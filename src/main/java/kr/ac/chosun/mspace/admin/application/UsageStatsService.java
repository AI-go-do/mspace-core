package kr.ac.chosun.mspace.admin.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.admin.application.UsageStatsPort.HourRow;
import kr.ac.chosun.mspace.admin.application.UsageStatsPort.RoomRow;
import kr.ac.chosun.mspace.admin.application.UsageStatsPort.SensorRow;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 관리자 이용 통계 (8차 회의: 이용률, 실사용률·노쇼율, 평균 이용 시간, 시간대별) + 센서 (CO2, 환기 알림, 재실).
 * 조회할 때만 GROUP BY 몇 번이라 예약·키패드 같은 평소 길에는 영향이 없다. 기간은 92일까지로 막아 쿼리 크기를 묶는다.
 */
@Service
class UsageStatsService implements UsageStatsUseCase {

    private static final int DEFAULT_DAYS = 7;

    private final UsageStatsPort port;
    private final SensorPolicy sensorPolicy;
    private final Clock clock;

    UsageStatsService(UsageStatsPort port, SensorPolicy sensorPolicy, Clock clock) {
        this.port = port;
        this.sensorPolicy = sensorPolicy;
        this.clock = clock;
    }

    @Override
    public UsageStats usage(long adminId, LocalDate from, LocalDate to) {
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        LocalDate end = to;
        if (end == null) {
            end = from == null ? today : from.plusDays(DEFAULT_DAYS - 1L);
        }
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        if (start.getYear() < 2000 || end.getYear() > 2100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "날짜는 2000–2100년 사이여야 합니다");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "from은 to보다 늦을 수 없습니다");
        }
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "한 번에 " + MAX_DAYS + "일까지 볼 수 있습니다");
        }
        Instant a = start.atStartOfDay(SEOUL).toInstant();
        Instant b = end.plusDays(1).atStartOfDay(SEOUL).toInstant();
        Instant now = clock.instant();
        Instant cap = now.isBefore(b) ? now : b;
        int threshold = sensorPolicy.co2().thresholdPpm();

        List<RoomRow> rows = port.rooms(a, b, cap);
        Map<Integer, Long> users = port.uniqueUsers(a, b);
        // 아직 오지 않은 기간이면 센서 값은 없다
        Map<Integer, SensorRow> sensors = cap.isAfter(a) ? port.sensors(a, cap, threshold) : Map.of();

        // 이용률 분모: 지난 시간만 (오늘·미래가 섞이면 아직 안 온 시간까지 나누지 않게)
        long minutesSoFar = Math.max(0, ChronoUnit.MINUTES.between(a, cap));
        List<Usage> rooms = new ArrayList<>();
        SensorRow all = SensorRow.EMPTY;
        RoomRow total = new RoomRow(0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        for (RoomRow r : rows) {
            SensorRow s = sensors.getOrDefault(r.roomId(), SensorRow.EMPTY);
            rooms.add(usage(r, users.getOrDefault(r.roomId(), 0L), s, threshold, minutesSoFar));
            total = add(total, r);
            all = add(all, s);
        }
        Usage whole = usage(total, users.getOrDefault(0, 0L), all, threshold, minutesSoFar * rows.size());
        whole = new Usage(null, null, whole.reservations(), whole.rates(), whole.minutes(), whole.shortUses(),
                whole.uniqueUsers(), whole.sensor());

        Map<Integer, HourRow> hours = new HashMap<>();
        port.byHour(a, b).forEach(h -> hours.put(h.hour(), h));
        List<Hour> byHour = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            HourRow x = hours.get(h);
            byHour.add(x == null ? new Hour(h, 0, 0) : new Hour(h, x.reservations(), x.used()));
        }
        return new UsageStats(start, end, days, whole, rooms, byHour);
    }

    private static Usage usage(RoomRow r, long uniqueUsers, SensorRow s, int threshold, long openMinutes) {
        long decided = r.total() - r.canceled() - r.pending();
        Rates rates = new Rates(rate(r.noShow(), decided), rate(r.used(), decided), rate(r.usedMinutes(), openMinutes));
        Minutes minutes = new Minutes(r.bookedMinutes(), r.usedMinutes(),
                r.used() == 0 ? null : Math.round((double) r.usedMinutes() / r.used()));
        Sensor sensor = new Sensor(s.co2Readings(),
                s.co2Readings() == 0 ? null : (int) Math.round((double) s.co2Sum() / s.co2Readings()),
                s.co2Max(), rate(s.co2Over(), s.co2Readings()), threshold, s.ventilationAlerts(),
                s.occupiedMinutes(), s.occupiedWithoutReservationMinutes());
        return new Usage(r.roomId(), r.roomName(),
                new Reservations(r.total(), r.canceled(), r.pending(), r.noShow(), r.used(), r.forced(),
                        r.awayReleased(), r.autoCheckedOut()),
                rates, minutes, r.shortUses(), uniqueUsers, sensor);
    }

    private static Double rate(long part, long whole) {
        return whole <= 0 ? null : Math.round(1000.0 * part / whole) / 1000.0;
    }

    private static RoomRow add(RoomRow a, RoomRow b) {
        return new RoomRow(0, null, a.total() + b.total(), a.canceled() + b.canceled(), a.pending() + b.pending(),
                a.noShow() + b.noShow(), a.used() + b.used(), a.forced() + b.forced(),
                a.awayReleased() + b.awayReleased(), a.autoCheckedOut() + b.autoCheckedOut(),
                a.bookedMinutes() + b.bookedMinutes(), a.usedMinutes() + b.usedMinutes(),
                a.shortUses() + b.shortUses());
    }

    private static SensorRow add(SensorRow a, SensorRow b) {
        Integer max;
        if (a.co2Max() == null) {
            max = b.co2Max();
        } else if (b.co2Max() == null) {
            max = a.co2Max();
        } else {
            max = Math.max(a.co2Max(), b.co2Max());
        }
        return new SensorRow(a.co2Readings() + b.co2Readings(), a.co2Sum() + b.co2Sum(), max, a.co2Over() + b.co2Over(),
                a.ventilationAlerts() + b.ventilationAlerts(), a.occupiedMinutes() + b.occupiedMinutes(),
                a.occupiedWithoutReservationMinutes() + b.occupiedWithoutReservationMinutes());
    }
}
