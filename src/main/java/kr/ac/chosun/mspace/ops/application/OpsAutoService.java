package kr.ac.chosun.mspace.ops.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.common.config.OpsPolicy;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.ops.application.OpsAutoPort.Room;
import kr.ac.chosun.mspace.ops.application.OpsAutoPort.Team;
import kr.ac.chosun.mspace.ops.domain.OpsRules;
import kr.ac.chosun.mspace.ops.domain.OpsRules.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * AI 자동 처리 5개 (11/12 지영 "관리자 카드 다 AI가 처리하게"). 1분마다 숫자 규칙({@link OpsRules})으로 본다.
 * <ul>
 *   <li>학생 알림: 미리 환기(사용 중 팀 전원) · 예약 전 더움(대표자) · 시간 넘김(자동 퇴실된 팀 전원). 예약마다 종류별 1번</li>
 *   <li>관리자 기록만: 센서 고장(끊김마다 1번) · 예약 없는 사용("있음"이 이어진 한 번마다 1번)</li>
 *   <li>재실 신호가 끊긴 방(offline-after-seconds)은 "있음"을 믿을 수 없어 시간 넘김·예약 없는 사용을 보지 않는다
 *       (끊기기 전 "있음"이 계속 남아 있어서)</li>
 *   <li>기록(ops_action UNIQUE)과 알림 저장은 한 트랜잭션. 서버 두 대가 같이 봐도 먼저 넣은 쪽만 알린다</li>
 * </ul>
 */
@Service
class OpsAutoService implements OpsAutoUseCase {

    static final String VENT_SOON = "VENT_SOON";
    static final String ROOM_HOT = "ROOM_HOT";
    static final String SENSOR_DOWN = "SENSOR_DOWN";
    static final String OVERTIME = "OVERTIME";
    static final String UNBOOKED = "UNBOOKED";

    private final OpsAutoPort port;
    private final OpsPolicy policy;
    private final SensorPolicy sensor;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;
    private final Clock clock;

    OpsAutoService(OpsAutoPort port, OpsPolicy policy, SensorPolicy sensor, NotificationInbox inbox,
                   TransactionOperations tx, Clock clock) {
        this.port = port;
        this.policy = policy;
        this.sensor = sensor;
        this.inbox = inbox;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public int check() {
        Instant now = clock.instant();
        List<Room> rooms = port.rooms();
        List<Notification> sent = new ArrayList<>();
        int done = ventSoon(now, sent) + roomHot(now, sent) + sensorDown(rooms, now) + overtime(rooms, now, sent)
                + unbooked(rooms, now);
        inbox.publish(sent);
        return done;
    }

    private int ventSoon(Instant now, List<Notification> sent) {
        OpsPolicy.VentSoon p = policy.ventSoon();
        Map<Integer, List<Point>> co2 = port.co2(now.minus(p.window()));
        int done = 0;
        for (Team t : port.inUse(now)) {
            List<Point> pts = co2.getOrDefault(t.roomId(), List.of());
            if (OpsRules.ventSoon(pts, now, p)) {
                int ppm = (int) pts.getFirst().value();
                String body = "%s 이산화탄소가 빠르게 오르고 있어요(지금 %dppm). 곧 기준(%dppm)을 넘을 것 같아 미리 알려요. 창문이나 문을 잠깐 열어 주세요."
                        .formatted(t.roomName(), ppm, p.thresholdPpm());
                done += act(VENT_SOON, t, t.startAt(), now, "CO2 %dppm, 최근 %d분 %d→%dppm".formatted(ppm,
                        p.window().toMinutes(), (int) pts.getLast().value(), ppm), t.userIds(), "곧 환기가 필요해요", body, sent);
            }
        }
        return done;
    }

    private int roomHot(Instant now, List<Notification> sent) {
        OpsPolicy.Hot p = policy.hot();
        Map<Integer, List<Point>> temps = port.temperature(now.minus(p.fresh()));
        int done = 0;
        for (Team t : port.startingBy(now, now.plus(p.before()))) {
            List<Point> pts = temps.getOrDefault(t.roomId(), List.of());
            if (OpsRules.roomHot(pts, now, p)) {
                double c = pts.getFirst().value();
                String body = "%s 지금 %.1f도로 더워요. 들어가면 에어컨이나 창문으로 온도를 낮춰 주세요.".formatted(t.roomName(), c);
                done += act(ROOM_HOT, t, t.startAt(), now, "온도 %.1f℃, 시작 %d분 전".formatted(c,
                        Duration.between(now, t.startAt()).toMinutes()), List.of(t.userIds().getFirst()), "방이 더워요", body, sent);
            }
        }
        return done;
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

    private int overtime(List<Room> rooms, Instant now, List<Notification> sent) {
        OpsPolicy.Overtime p = policy.overtime();
        int done = 0;
        for (Team t : port.autoCheckedOut(now.minus(p.within()), now.minus(p.after()).plusSeconds(1))) {
            Room r = rooms.stream().filter(x -> x.id() == t.roomId()).findFirst().orElse(null);
            if (r == null || offline(r, now) || !r.occupied()) {
                continue;
            }
            // 끝날 때 "있음"은 continuous 판에서 since ≤ endAt으로 이미 본다 (atEnd 판은 측정에서만 씀)
            if (OpsRules.overtime(t.endAt(), !r.since().isAfter(t.endAt()), true, r.since(), now,
                    port.nextStart(t.roomId(), t.endAt()), p)) {
                long late = Duration.between(t.endAt(), now).toMinutes();
                String body = "%s 예약 시간이 %d분 지났는데 방에 아직 사람이 있는 것 같아요. 다음 사람을 위해 정리하고 나가 주세요. 더 쓰려면 다시 예약해 주세요."
                        .formatted(t.roomName(), late);
                done += act(OVERTIME, t, t.startAt(), now, "끝나고 %d분째 사람 있음".formatted(late), t.userIds(),
                        "이용 시간이 지났어요", body, sent);
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
        return r.lastReceivedAt() == null || !now.isBefore(r.lastReceivedAt().plusSeconds(sensor.offlineAfterSeconds()));
    }

    /** 기록하고 처음이면 알림. 처리했으면 1 */
    private int act(String kind, Team t, Instant startedAt, Instant now, String detail, List<Long> to, String title,
                    String body, List<Notification> sent) {
        List<Notification> stored = tx.execute(s -> {
            if (!port.record(kind, t.roomId(), t.reservationId(), startedAt, now, detail)) {
                return null;
            }
            List<Notification> out = inbox.store(to.stream()
                    .map(u -> new NewNotification(u, t.reservationId(), kind, title, body)).toList(), now);
            port.notified(kind, t.roomId(), startedAt, out.size());
            return out;
        });
        if (stored == null) {
            return 0;
        }
        sent.addAll(stored);
        return 1;
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
