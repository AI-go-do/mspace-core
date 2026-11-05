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
import kr.ac.chosun.mspace.common.config.ReminderPolicy;
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

    JdbcReminderAdapter(JdbcClient jdbc, ReminderPolicy policy, SensorPolicy sensor) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.sensor = sensor;
    }

    /** 고른 줄: 예약·사람과 문구에 쓸 방 이름·시각 */
    private record Due(long reservationId, long userId, String room, Instant startAt, Instant endAt,
                       Instant checkinDeadline, boolean canExtend) {
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
                    "입실 %d분 전입니다".formatted(policy.startBeforeMinutes()),
                    "%s %s 시작이에요. %s까지 키패드에 번호를 넣지 않으면 노쇼로 예약이 풀려요."
                            .formatted(d.room(), HH_MM.format(d.startAt()), HH_MM.format(d.checkinDeadline()))));
        }
        // FR-NTF-102 종료 N분 전: 사용 중, 끝 N분 전 ~ 끝 전. 전원. 다음 칸이 비어 있고 아직 연장 안 했으면 연장 안내
        for (Due d : claim(ReminderKind.END_SOON, """
                r.status = 'IN_USE'
                AND r.end_at > :now AND r.end_at <= :horizon""",
                now, policy.endBeforeMinutes())) {
            created.add(save(d, ReminderKind.END_SOON, now,
                    "종료 %d분 전입니다".formatted(policy.endBeforeMinutes()),
                    "%s %s에 끝나요. 퇴실 전에 자리를 정리해 주세요.%s".formatted(d.room(), HH_MM.format(d.endAt()),
                            d.canExtend() ? " 다음 칸이 비어 있어 연장할 수 있어요." : "")));
        }
        // FR-NTF-104 노쇼 N분 전: 아직 입실 전, 입실 마감 N분 전 ~ 마감 전(끝나기 전). 번호를 가진 대표자만
        for (Due d : claim(ReminderKind.NO_SHOW_SOON, """
                r.status = 'BOOKED' AND p.is_owner AND r.end_at > :now
                AND r.checkin_deadline > :now AND r.checkin_deadline <= :horizon""",
                now, policy.noShowBeforeMinutes())) {
            created.add(save(d, ReminderKind.NO_SHOW_SOON, now,
                    "노쇼 %d분 전입니다".formatted(policy.noShowBeforeMinutes()),
                    "%s %s까지 키패드에 번호를 넣지 않으면 방이 풀려요."
                            .formatted(d.room(), HH_MM.format(d.checkinDeadline()))));
        }
        // FR-DEV-103 자리 비움: 사용 중인데 센서가 N분 연속 비어 있음(끊긴 센서는 "있음"으로 봐서 제외). 대표자만.
        // 비어 있음 시작 = 비어 있게 된 관측 시각, 끊겼다 돌아온 시각, 입실 시각 중 가장 늦은 것 (V11 room_presence)
        int away = sensor.away().notifyAfterMinutes();
        for (Due d : claim(ReminderKind.AWAY_SOON, """
                r.status = 'IN_USE' AND p.is_owner AND r.end_at > :now
                AND EXISTS (SELECT 1 FROM room_presence rp
                            WHERE rp.room_id = r.room_id AND NOT rp.occupied AND rp.last_received_at >= :aliveAfter
                              AND greatest(rp.changed_at, rp.trusted_from, r.checked_in_at) <= :horizon)""",
                now, -away)) {
            created.add(save(d, ReminderKind.AWAY_SOON, now, "자리에 안 계시네요",
                    "%s이(가) %d분째 비어 있어요. %d분 안에 돌아오지 않으면 자동으로 퇴실돼요.".formatted(d.room(), away,
                            sensor.away().releaseAfterMinutes() - away)));
        }
        return created;
    }

    private List<Due> claim(ReminderKind kind, String condition, Instant now, int beforeMinutes) {
        // canExtend: 같은 방에 지금 예약 끝부터 30분 안에 시작하는 끝나지 않은 예약이 없고, 이미 연장한 예약(continued_from_id)이 아님.
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
                              AND n.start_at < r.end_at + interval '30 minutes' AND n.end_at > r.end_at)) AS can_extend
                FROM logged l
                JOIN reservation r ON r.id = l.reservation_id
                JOIN rooms rm ON rm.id = r.room_id
                ORDER BY l.reservation_id, l.user_id
                """.formatted(condition))
                .param("now", Timestamp.from(now))
                .param("horizon", Timestamp.from(now.plus(Duration.ofMinutes(beforeMinutes))))
                .param("kind", kind.name())
                .param("aliveAfter", Timestamp.from(now.minusSeconds(sensor.offlineAfterSeconds())))
                .query(JdbcReminderAdapter::due)
                .list();
    }

    private static Due due(ResultSet rs, int n) throws SQLException {
        return new Due(rs.getLong("reservation_id"), rs.getLong("user_id"), rs.getString("room"),
                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                rs.getTimestamp("checkin_deadline").toInstant(), rs.getBoolean("can_extend"));
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
