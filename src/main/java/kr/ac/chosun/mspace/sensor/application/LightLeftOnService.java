package kr.ac.chosun.mspace.sensor.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.chosun.mspace.common.config.LightLeftOnPolicy;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.ops.application.OpsActionRecorder;
import kr.ac.chosun.mspace.sensor.application.LightLeftOnPort.LastTeam;
import kr.ac.chosun.mspace.sensor.application.RoomSignalsQuery.Signals;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 빈 방 불 켜짐 (11/12 지영 "마지막 팀한테는 알리고 관리자는 관리자 페이지에 불 켜짐 보이게").
 * <ul>
 *   <li>켜짐: 빈 방(재실 없음·지금 예약 없음)인데 최근 조도 값이 기준 이상. 조도가 끊긴 방(offline-after-seconds 넘게
 *       안 옴)은 모르는 것이라 켜짐으로 보지 않는다</li>
 *   <li>처음 켜짐을 본 시각을 메모리에 들고 있다가 after만큼 이어지면 "불 켜짐". 한 번이라도 조건이 깨지면 처음부터.
 *       조도 최신 값도 메모리라 서버 한 대 기준이고, 다시 켜면 처음부터 센다(늦어질 뿐 틀리지 않음)</li>
 *   <li>알림: 마지막으로 퇴실한 팀(대표자·참여자)에게 예약마다 1번. 처음 켜짐을 본 시각보다 team-within 넘게 전에
 *       퇴실했으면 그 팀이 켜 둔 게 아닐 수 있어 보내지 않는다. 관리자는 알림 없이 "지금" 탭 표시만</li>
 *   <li>불 켜짐 한 번(처음 켜짐을 본 시각)마다 "AI가 처리한 일"(ops_action LIGHT_LEFT_ON)에 1줄 남긴다
 *       (11/13 지영). 기록과 알림 저장은 한 트랜잭션이라 서버 두 대가 같이 봐도 먼저 넣은 쪽만 알린다</li>
 * </ul>
 */
@Service
class LightLeftOnService implements LightLeftOnUseCase, LightLeftOnQuery {

    static final String KIND = "LIGHT_LEFT_ON";

    private final LightLeftOnPort port;
    private final RoomSignalsQuery signals;
    private final LightLeftOnPolicy policy;
    private final SensorPolicy sensor;
    private final NotificationInbox inbox;
    private final OpsActionRecorder ops;
    private final TransactionOperations tx;
    private final Clock clock;
    private final Map<Integer, Instant> onSince = new ConcurrentHashMap<>();

    LightLeftOnService(LightLeftOnPort port, RoomSignalsQuery signals, LightLeftOnPolicy policy, SensorPolicy sensor,
                       NotificationInbox inbox, OpsActionRecorder ops, TransactionOperations tx, Clock clock) {
        this.port = port;
        this.signals = signals;
        this.policy = policy;
        this.sensor = sensor;
        this.inbox = inbox;
        this.ops = ops;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public int check() {
        Instant now = clock.instant();
        Instant freshAfter = now.minusSeconds(sensor.offlineAfterSeconds());
        Set<Integer> idle = port.idleRooms(now);
        Map<Integer, Signals> latest = signals.latest();
        onSince.keySet().removeIf(room -> !lit(idle, latest, room, freshAfter));
        for (Integer room : idle) {
            if (lit(idle, latest, room, freshAfter)) {
                onSince.putIfAbsent(room, now);
            }
        }
        List<Notification> stored = new ArrayList<>();
        new HashMap<>(onSince).forEach((room, since) -> {
            if (!since.plus(policy.after()).isAfter(now)) {
                Integer lux = latest.get(room).light();
                List<Notification> sent = tx.execute(s -> leftOn(room, since, now, lux));
                stored.addAll(sent == null ? List.of() : sent);
            }
        });
        inbox.publish(stored);
        return stored.size();
    }

    @Override
    public Map<Integer, Instant> leftOn() {
        Instant now = clock.instant();
        Map<Integer, Instant> out = new HashMap<>();
        onSince.forEach((room, since) -> {
            if (!since.plus(policy.after()).isAfter(now)) {
                out.put(room, since);
            }
        });
        return Map.copyOf(out);
    }

    private boolean lit(Set<Integer> idle, Map<Integer, Signals> latest, int room, Instant freshAfter) {
        Signals s = latest.get(room);
        return idle.contains(room) && s != null && s.light() != null && s.lightAt().isAfter(freshAfter)
                && s.light() >= policy.threshold();
    }

    /** 불 켜짐 한 번을 기록하고, 처음 남긴 때만 마지막 팀에게 알린다. 이미 남긴 켜짐이면 빈 목록 */
    private List<Notification> leftOn(int room, Instant since, Instant now, Integer lux) {
        LastTeam team = port.lastTeam(room)
                .filter(t -> !t.notified() && !t.checkedOutAt().isBefore(since.minus(policy.teamWithin())))
                .orElse(null);
        long minutes = policy.after().toMinutes();
        String detail = "빈 방 조도 %d(기준 %d), %d분 넘게 켜짐".formatted(lux, policy.threshold(), minutes);
        if (!ops.record(KIND, room, team == null ? null : team.reservationId(), since, now, detail)) {
            return List.of();
        }
        if (team == null) {
            return List.of();
        }
        String body = "%s 불이 퇴실 뒤 %d분 넘게 켜져 있어요. 가까이 있다면 꺼 주시고, 다음부터는 나갈 때 꺼 주세요."
                .formatted(team.roomName(), minutes);
        List<Notification> sent = inbox.store(team.userIds().stream()
                .map(u -> new NewNotification(u, team.reservationId(), KIND, "방 불이 켜져 있어요", body))
                .toList(), now);
        ops.notified(KIND, room, since, sent.size());
        return sent;
    }
}
