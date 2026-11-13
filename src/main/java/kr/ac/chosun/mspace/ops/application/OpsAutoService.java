package kr.ac.chosun.mspace.ops.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kr.ac.chosun.mspace.common.config.OpsPolicy;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.ops.application.OpsAutoPort.Room;
import kr.ac.chosun.mspace.ops.domain.OpsRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * AI 자동 처리 (11/12 지영 "관리자 카드 다 AI가 처리하게"). 1분마다 숫자 규칙({@link OpsRules})으로 본다.
 * <ul>
 *   <li>관리자 기록만: 센서 고장(끊김마다 1번) · 예약 없는 사용("있음"이 이어진 한 번마다 1번)</li>
 *   <li>재실 신호가 끊긴 방(offline-after-seconds)은 "있음"을 믿을 수 없어 예약 없는 사용을 보지 않는다
 *       (끊기기 전 "있음"이 계속 남아 있어서)</li>
 *   <li>기록은 ops_action UNIQUE라 서버 두 대가 같이 봐도 한 번만 남는다</li>
 * </ul>
 */
@Service
class OpsAutoService implements OpsAutoUseCase {

    static final String SENSOR_DOWN = "SENSOR_DOWN";
    static final String UNBOOKED = "UNBOOKED";

    private final OpsAutoPort port;
    private final OpsPolicy policy;
    private final SensorPolicy sensor;
    private final TransactionOperations tx;
    private final Clock clock;

    OpsAutoService(OpsAutoPort port, OpsPolicy policy, SensorPolicy sensor, TransactionOperations tx, Clock clock) {
        this.port = port;
        this.policy = policy;
        this.sensor = sensor;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public int check() {
        Instant now = clock.instant();
        List<Room> rooms = port.rooms();
        return sensorDown(rooms, now) + unbooked(rooms, now);
    }

    private int sensorDown(List<Room> rooms, Instant now) {
        OpsPolicy.SensorDown p = policy.sensorDown();
        int done = 0;
        for (Room r : rooms) {
            // 신호를 보내 본 다른 방이 없으면(센서가 이 방에만 있음) "모두 끊김"을 알 수 없어 고장으로 본다 (11/12 수연 리뷰)
            List<Room> others = rooms.stream().filter(o -> o.id() != r.id() && o.lastReceivedAt() != null).toList();
            boolean other = others.isEmpty()
                    || others.stream().anyMatch(o -> now.isBefore(o.lastReceivedAt().plus(p.after())));
            if (OpsRules.sensorDown(r.lastReceivedAt(), now, other, p)) {
                String detail = "마지막 신호 %d분 전%s".formatted(Duration.between(r.lastReceivedAt(), now).toMinutes(),
                        others.isEmpty() ? "" : ", 다른 방은 신호 있음");
                done += Boolean.TRUE.equals(tx.execute(s -> port.record(SENSOR_DOWN, r.id(), null, r.lastReceivedAt(),
                        now, detail))) ? 1 : 0;
            }
        }
        return done;
    }

    private int unbooked(List<Room> rooms, Instant now) {
        OpsPolicy.Unbooked p = policy.unbooked();
        var spans = port.bookings(now.minus(p.lateGrace()), now.plus(p.earlyGrace()));
        int done = 0;
        for (Room r : rooms) {
            if (!r.occupied() || offline(r, now)
                    || !OpsRules.unbooked(true, r.since(), now, spans.getOrDefault(r.id(), List.of()), p)) {
                continue;
            }
            String detail = "예약 없이 %d분째 사람 있음".formatted(Duration.between(r.since(), now).toMinutes());
            done += Boolean.TRUE.equals(tx.execute(s -> port.record(UNBOOKED, r.id(), null, r.since(), now, detail)))
                    ? 1 : 0;
        }
        return done;
    }

    private boolean offline(Room r, Instant now) {
        // 재실 신호만 본다: CO2만 들어와도 오래된 "있음"을 믿지 않게
        return r.presenceReceivedAt() == null
                || !now.isBefore(r.presenceReceivedAt().plusSeconds(sensor.offlineAfterSeconds()));
    }

    @Override
    public List<OpsAction> actions(long adminId, LocalDate date) {
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        LocalDate day = date == null ? LocalDate.ofInstant(clock.instant(), SEOUL) : date;
        Instant from = day.atStartOfDay(SEOUL).toInstant();
        return port.actions(from, day.plusDays(1).atStartOfDay(SEOUL).toInstant()).stream()
                .map(r -> new OpsAction(r.id(), r.kind(), r.roomId(), r.roomName(), r.reservationId(),
                        r.startedAt().atZone(SEOUL), r.detectedAt().atZone(SEOUL), r.detail(), r.notified()))
                .toList();
    }
}
