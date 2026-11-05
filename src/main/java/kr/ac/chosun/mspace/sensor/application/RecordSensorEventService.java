package kr.ac.chosun.mspace.sensor.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.sensor.application.SensorEventPort.Co2Reading;
import kr.ac.chosun.mspace.sensor.application.SensorEventPort.Presence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 센서 이벤트 한 건 처리. 같은 방 이벤트는 room_presence 줄 잠금으로 한 줄로 세운다(동시에 와도 순서대로).
 * <p>
 * 재실 (FR-DEV-101·102):
 * <ul>
 *   <li>상태는 observedAt이 지금 상태보다 새로울 때만 바꾼다. 늦게 온 옛 이벤트는 상태를 덮지 않는다</li>
 *   <li>1분 넘게 신호가 없으면 끊김 → "있음"으로 본다. 다시 오면 끊긴 시각(마지막 받은 시각 + 1분)보다 옛 관측은
 *       상태를 바꾸지 않고, 10·15분 타이머는 다시 받은 시각(trusted_from)부터 잰다 ("틀리면 있음 쪽으로")</li>
 *   <li>10초마다 오는 "그대로예요" 신호는 sensor_event에 남기지 않고 받은 시각만 고친다. 상태가 바뀐 이벤트,
 *       처음 이벤트, 늦게 온 다른 상태 이벤트만 남긴다 (정확도 측정용)</li>
 * </ul>
 * CO2 (FR-DEV-104): 모든 값을 남기고, 최근 3개가 모두 1000ppm 초과·간격 30~90초·처음과 끝 100초 이상이면
 * 지금 그 방을 쓰는 사람 전원에게 "환기해 주세요". 같은 방 30분에 한 번.
 * <p>
 * 알림은 DB 커밋 뒤에 바깥 채널로 내보낸다 (메일이 느려도 방 줄 잠금을 쥐지 않게).
 */
@Service
class RecordSensorEventService implements RecordSensorEventUseCase {

    static final String VENTILATE = "VENTILATE";

    private final SensorEventPort port;
    private final NotificationInbox inbox;
    private final SensorPolicy policy;
    private final TransactionOperations tx;
    private final Clock clock;

    RecordSensorEventService(SensorEventPort port, NotificationInbox inbox, SensorPolicy policy,
                             TransactionOperations tx, Clock clock) {
        this.port = port;
        this.inbox = inbox;
        this.policy = policy;
        this.tx = tx;
        this.clock = clock;
    }

    private record Outcome(Result result, List<Notification> notifications) {
        static final Outcome DUPLICATE = new Outcome(Result.DUPLICATE, List.of());
        static final Outcome STORED = new Outcome(Result.STORED, List.of());
    }

    @Override
    public Result record(SensorEvent e) {
        Instant now = clock.instant();
        validate(e, now);
        Outcome outcome = tx.execute(status -> port.exists(e.eventId()) ? Outcome.DUPLICATE
                : e.kind() == Kind.OCCUPANCY ? occupancy(e, now) : co2(e, now));
        inbox.publish(outcome.notifications());
        return outcome.result();
    }

    private void validate(SensorEvent e, Instant now) {
        boolean shape = switch (e.kind()) {
            case OCCUPANCY -> e.occupied() != null && e.ppm() == null;
            case CO2 -> e.ppm() != null && e.ppm() >= 0 && e.ppm() <= 10_000 && e.occupied() == null;
        };
        if (!shape || !port.roomExists(e.roomId()) || e.observedAt().isAfter(now.plus(Duration.ofMinutes(policy.futureToleranceMinutes())))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private Outcome occupancy(SensorEvent e, Instant now) {
        Presence p = port.lockPresence(e.roomId());
        Duration offline = Duration.ofSeconds(policy.offlineAfterSeconds());
        // 끊겼다 돌아옴: 끊긴 시각(마지막 받은 시각 + 1분) 뒤에 본 이벤트만 상태를 바꿀 수 있고,
        // 비어 있음 타이머는 지금(다시 받은 시각)부터 다시 잰다. 끊긴 동안은 "있음"으로 봤으니까
        boolean resumed = Duration.between(p.lastReceivedAt(), now).compareTo(offline) > 0;
        Instant trustedFrom = resumed ? now : p.trustedFrom();
        Instant notBefore = resumed ? p.lastReceivedAt().plus(offline) : p.trustedFrom();
        boolean first = p.lastObservedAt().equals(Instant.EPOCH);
        boolean fresh = e.observedAt().isAfter(p.lastObservedAt()) && !e.observedAt().isBefore(notBefore);

        boolean occupied = fresh ? e.occupied() : p.occupied();
        boolean changed = fresh && e.occupied() != p.occupied();
        // 기록할 것: 상태를 바꾼 것·처음 것, 또는 늦게 와서 반영은 못 했지만 지금과 다른 상태를 본 것
        boolean keep = fresh ? (changed || first) : e.occupied() != occupied;
        if (keep && !port.insert(e, now)) {
            return Outcome.DUPLICATE;   // 같은 eventId가 동시에 두 번 와서 다른 쪽이 먼저 넣음
        }
        port.savePresence(new Presence(p.roomId(), occupied,
                changed || (fresh && first) ? e.observedAt() : p.changedAt(),
                trustedFrom,
                fresh ? e.observedAt() : p.lastObservedAt(),
                now.isAfter(p.lastReceivedAt()) ? now : p.lastReceivedAt(),
                p.ventilationAlertAt()));
        return Outcome.STORED;
    }

    private Outcome co2(SensorEvent e, Instant now) {
        if (!port.insert(e, now)) {
            return Outcome.DUPLICATE;
        }
        SensorPolicy.Co2 rule = policy.co2();
        if (e.ppm() <= rule.thresholdPpm()) {
            return Outcome.STORED;
        }
        Presence p = port.lockPresence(e.roomId());   // 같은 방 CO2 판단을 한 줄로 (알림 두 번 방지)
        List<Co2Reading> recent = port.recentCo2(e.roomId(), rule.consecutive());
        if (!highInARow(recent, e.eventId(), rule)) {
            return Outcome.STORED;
        }
        Instant newest = recent.getFirst().observedAt();
        if (p.ventilationAlertAt() != null
                && newest.isBefore(p.ventilationAlertAt().plus(Duration.ofMinutes(rule.alertEveryMinutes())))) {
            return Outcome.STORED;   // 같은 방 30분에 한 번. 지난 알림보다 옛 값도 여기서 걸러진다
        }
        var occupants = port.occupantsNow(e.roomId(), now);
        if (occupants.isEmpty()) {
            return Outcome.STORED;   // 지금 쓰는 사람이 없으면 알릴 사람도 없다 (알림 시각도 그대로)
        }
        port.savePresence(new Presence(p.roomId(), p.occupied(), p.changedAt(), p.trustedFrom(), p.lastObservedAt(),
                p.lastReceivedAt(), newest));
        List<NewNotification> items = occupants.stream()
                .map(o -> new NewNotification(o.userId(), o.reservationId(), VENTILATE, "환기해 주세요",
                        "%s 이산화탄소가 %dppm을 %d분 넘게 넘었어요. 창문이나 문을 잠깐 열어 주세요."
                                .formatted(o.roomName(), rule.thresholdPpm(), rule.consecutive())))
                .toList();
        return new Outcome(Result.STORED, inbox.store(items, now));
    }

    /** 최근 n개(늦은 것부터)가 모두 기준 초과, 방금 값 포함, 이웃 간격 30~90초, 처음과 끝 100초 이상 */
    static boolean highInARow(List<Co2Reading> recent, String eventId, SensorPolicy.Co2 rule) {
        if (recent.size() < rule.consecutive() || recent.stream().noneMatch(r -> r.eventId().equals(eventId))) {
            return false;
        }
        for (int i = 0; i < recent.size(); i++) {
            if (recent.get(i).ppm() <= rule.thresholdPpm()) {
                return false;
            }
            if (i > 0) {
                long gap = Duration.between(recent.get(i).observedAt(), recent.get(i - 1).observedAt()).toSeconds();
                if (gap < rule.minGapSeconds() || gap > rule.maxGapSeconds()) {
                    return false;
                }
            }
        }
        long span = Duration.between(recent.getLast().observedAt(), recent.getFirst().observedAt()).toSeconds();
        return span >= rule.minSpanSeconds();
    }
}
