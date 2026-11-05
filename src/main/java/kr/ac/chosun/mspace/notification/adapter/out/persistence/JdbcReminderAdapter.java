package kr.ac.chosun.mspace.notification.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.common.config.ReminderPolicy;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.ReminderPort;
import kr.ac.chosun.mspace.notification.domain.ReminderKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 알림 3종. 종류마다 "보낼 때가 된 예약 × 받을 사람"을 골라 notification_log에 넣고
 * (ON CONFLICT DO NOTHING RETURNING → 처음 들어간 줄만 돌아옴), 그 줄만 알림함(notification)에 넣는다.
 * <p>
 * 두 서버가 동시에 돌면: 같은 (예약, 사람, 종류) PK를 먼저 넣은 쪽이 커밋할 때까지 다른 쪽이 기다렸다가
 * DO NOTHING으로 빠진다. 그래서 알림은 정확히 한 번 (V1 끝 주석 9번 방식).
 * <p>
 * 받을 사람은 reservation_participant(대표자 포함, is_owner)에서 active인 사람. 상태가 끝나면 트리거가 active를 끈다.
 * 시각은 "N분 전 ~ 그 시각 전" 구간이라, 정리가 1분 늦게 돌아도 놓치지 않고, 구간이 지나면 보내지 않는다.
 */
@Component
class JdbcReminderAdapter implements ReminderPort {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(SEOUL);

    private final JdbcClient jdbc;
    private final ReminderPolicy policy;
    private final SensorPolicy sensor;
    private final ReservationPolicy slots;

    JdbcReminderAdapter(JdbcClient jdbc, ReminderPolicy policy, SensorPolicy sensor, ReservationPolicy slots) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.sensor = sensor;
        this.slots = slots;
    }

    /** 고른 줄: 예약·사람과 문구에 쓸 방 이름·시각 */
    private record Due(long reservationId, long userId, String room, Instant startAt, Instant endAt,
                       Instant checkinDeadline, boolean canExtend, Instant awaySince) {
    }

    @Override
    @Transactional
    public List<Notification> claimDue(Instant now) {
        List<Notification> created = new ArrayList<>();
        // FR-NTF-101 입실 N분 전: 미리 예약(즉시 시작 제외), 아직 입실 전, 시작 N분 전 ~ 시작 전. 전원
        for (Due d : claim(ReminderKind.START_SOON, """
                r.status = 'BOOKED' AND NOT r.is_instant
                AND r.start_at > :now AND r.start_at <= :horizon""",
                now, policy.startBeforeMinutes())) {
            created.add(save(d, ReminderKind.START_SOON, now,
                    "입실 %d분 전입니다".formatted(minutesLeft(now, d.startAt(), policy.startBeforeMinutes())),
                    "%s %s 시작이에요. %s까지 키패드에 번호를 넣지 않으면 노쇼로 예약이 풀려요."
                            .formatted(d.room(), HH_MM.format(d.startAt()), HH_MM.format(d.checkinDeadline()))));
        }
        // FR-NTF-102 종료 N분 전: 사용 중, 끝 N분 전 ~ 끝 전. 전원. 다음 칸이 비어 있고 아직 연장 안 했으면 연장 안내
        for (Due d : claim(ReminderKind.END_SOON, """
                r.status = 'IN_USE'
                AND r.end_at > :now AND r.end_at <= :horizon""",
                now, policy.endBeforeMinutes())) {
            created.add(save(d, ReminderKind.END_SOON, now,
                    "종료 %d분 전입니다".formatted(minutesLeft(now, d.endAt(), policy.endBeforeMinutes())),
                    "%s %s에 끝나요. 퇴실 전에 자리를 정리해 주세요.%s".formatted(d.room(), HH_MM.format(d.endAt()),
                            d.canExtend() ? " 다음 칸이 비어 있어 연장할 수 있어요." : "")));
        }
        // FR-NTF-104 노쇼 N분 전: 아직 입실 전, 입실 마감 N분 전 ~ 마감 전(끝나기 전). 번호를 가진 대표자만
        for (Due d : claim(ReminderKind.NO_SHOW_SOON, """
                r.status = 'BOOKED' AND p.is_owner AND r.end_at > :now
                AND r.checkin_deadline > :now AND r.checkin_deadline <= :horizon""",
                now, policy.noShowBeforeMinutes())) {
            created.add(save(d, ReminderKind.NO_SHOW_SOON, now,
                    "노쇼 %d분 전입니다".formatted(minutesLeft(now, d.checkinDeadline(), policy.noShowBeforeMinutes())),
                    "%s %s까지 키패드에 번호를 넣지 않으면 방이 풀려요."
                            .formatted(d.room(), HH_MM.format(d.checkinDeadline()))));
        }
        // FR-DEV-103 자리 비움: 사용 중인데 센서가 N분 연속 비어 있음(끊긴 센서는 "있음"으로 봐서 제외). 대표자만.
        // 비어 있음 시작 = 비어 있게 된 관측 시각, 끊겼다 돌아온 시각, 입실 시각 중 가장 늦은 것 (V11 room_presence)
        // "아직 있어요"를 이미 누른 예약은 알림 없이 정리(releaseAway)가 바로 퇴실시킨다 (11/5 지영)
        int away = sensor.away().notifyAfterMinutes();
        String stillHereHint = sensor.away().stillHereEnabled()
                ? " 계시다면 '아직 있어요'를 눌러 주세요(1번만, 누른 뒤 %d분 더 비면 바로 퇴실돼요)."
                        .formatted(sensor.away().releaseAfterStillHereMinutes())
                : "";
        for (Due d : claim(ReminderKind.AWAY_SOON, """
                r.status = 'IN_USE' AND p.is_owner AND r.end_at > :now AND r.still_here_at IS NULL
                AND EXISTS (SELECT 1 FROM room_presence rp
                            WHERE rp.room_id = r.room_id AND NOT rp.occupied AND rp.last_received_at >= :aliveAfter
                              AND greatest(rp.changed_at, rp.trusted_from, r.checked_in_at) <= :horizon)""",
                now, -away)) {
            // 분은 설정값이 아니라 실제 값: 정리가 늦게 돌면 비어 있던 시간은 더 길고, 퇴실까지는 더 짧다.
            // 예약 끝이 자리 비움 퇴실보다 먼저면 끝 시각까지 (그때 자동 퇴실)
            Instant since = d.awaySince() != null ? d.awaySince() : now.minus(Duration.ofMinutes(away));
            Instant releaseAt = since.plus(Duration.ofMinutes(sensor.away().releaseAfterMinutes()));
            Instant outAt = releaseAt.isBefore(d.endAt()) ? releaseAt : d.endAt();
            long emptyMinutes = Math.max(away, Duration.between(since, now).toMinutes());
            created.add(save(d, ReminderKind.AWAY_SOON, now, "자리에 안 계시네요",
                    "%s이(가) %d분째 비어 있어요. %d분 안에 돌아오지 않으면 자동으로 퇴실돼요.%s".formatted(d.room(), emptyMinutes,
                            minutesLeft(now, outAt, sensor.away().releaseAfterMinutes()), stillHereHint)));
        }
        return created;
    }

    /**
     * 제목에 쓸 "N분 전"의 N: 설정값이 아니라 지금부터 그 시각까지 실제로 남은 분.
     * 구간 중간에 들어온 예약(예: 즉시 시작으로 55분에 입실, 6시 종료)은 첫 정리에서 바로 고르므로
     * 설정값(10분)을 그대로 쓰면 "종료 10분 전"인데 실제로는 5분 남는다 (11/5 지영 테스트).
     * 초는 올림(4분 30초 → 5분), 1분 ~ 설정값 사이로 자른다.
     */
    static int minutesLeft(Instant now, Instant target, int maxMinutes) {
        long seconds = Duration.between(now, target).toSeconds();
        long minutes = (seconds + 59) / 60;
        return (int) Math.max(1, Math.min(maxMinutes, minutes));
    }

    private List<Due> claim(ReminderKind kind, String condition, Instant now, int beforeMinutes) {
        // canExtend: 같은 방에 지금 예약 끝부터 한 칸(slotMinutes, 30분) 안에 시작하는 끝나지 않은 예약이 없고, 이미 연장한 예약(continued_from_id)이 아님.
        // 실제 연장 가능 여부(1회·시간 창)는 연장 API가 다시 확인한다. 여기는 안내 문구용
        return jdbc.sql("""
                WITH due AS (
                    SELECT r.id AS reservation_id, p.user_id
                    FROM reservation r
                    JOIN reservation_participant p ON p.reservation_id = r.id AND p.active
                    WHERE %s
                ), logged AS (
                    INSERT INTO notification_log (reservation_id, user_id, kind, sent_at)
                    SELECT reservation_id, user_id, :kind, :now FROM due
                    ON CONFLICT DO NOTHING
                    RETURNING reservation_id, user_id
                )
                SELECT l.reservation_id, l.user_id, rm.name AS room, r.start_at, r.end_at, r.checkin_deadline,
                       (r.continued_from_id IS NULL AND NOT EXISTS (
                            SELECT 1 FROM reservation n
                            WHERE n.room_id = r.room_id AND n.id <> r.id AND n.status IN ('BOOKED', 'IN_USE')
                              AND n.start_at < r.end_at + make_interval(mins => :slot) AND n.end_at > r.end_at)) AS can_extend,
                       (SELECT greatest(rp.changed_at, rp.trusted_from, r.checked_in_at)
                        FROM room_presence rp WHERE rp.room_id = r.room_id AND NOT rp.occupied) AS away_since
                FROM logged l
                JOIN reservation r ON r.id = l.reservation_id
                JOIN rooms rm ON rm.id = r.room_id
                ORDER BY l.reservation_id, l.user_id
                """.formatted(condition))
                .param("now", Timestamp.from(now))
                .param("horizon", Timestamp.from(now.plus(Duration.ofMinutes(beforeMinutes))))
                .param("kind", kind.name())
                .param("aliveAfter", Timestamp.from(now.minusSeconds(sensor.offlineAfterSeconds())))
                .param("slot", slots.slotMinutes())
                .query(JdbcReminderAdapter::due)
                .list();
    }

    private static Due due(ResultSet rs, int n) throws SQLException {
        return new Due(rs.getLong("reservation_id"), rs.getLong("user_id"), rs.getString("room"),
                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                rs.getTimestamp("checkin_deadline").toInstant(), rs.getBoolean("can_extend"),
                Optional.ofNullable(rs.getTimestamp("away_since")).map(Timestamp::toInstant).orElse(null));
    }

    private Notification save(Due d, ReminderKind kind, Instant now, String title, String body) {
        long id = jdbc.sql("""
                INSERT INTO notification (user_id, reservation_id, kind, title, body, created_at)
                VALUES (:user, :reservation, :kind, :title, :body, :now)
                RETURNING id
                """)
                .param("user", d.userId()).param("reservation", d.reservationId()).param("kind", kind.name())
                .param("title", title).param("body", body).param("now", Timestamp.from(now))
                .query(Long.class).single();
        return new Notification(id, d.userId(), d.reservationId(), kind.name(), title, body, now, null);
    }
}
