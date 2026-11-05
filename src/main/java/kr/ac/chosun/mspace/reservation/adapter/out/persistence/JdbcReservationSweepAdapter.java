package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import kr.ac.chosun.mspace.common.config.NoShowPenaltyPolicy;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.reservation.application.ReservationSweepPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQL은 V1__init_schema.sql 끝 "6. 스케줄러(1분 주기)"와 같다.
 * 다른 트랜잭션이 잡고 있는 예약 줄은 FOR UPDATE SKIP LOCKED로 건너뛰고 나머지는 바로 처리한다.
 * 그래도 트리거가 건드리는 참여자 줄 등에서 기다릴 수 있어서 UPDATE마다 잠금 대기 상한
 * (mspace.reservation.lock-timeout, 예약 저장과 같은 값)을 건다. 넘으면 CannotAcquireLockException → 서비스가 다음 번에 다시.
 */
@Component
class JdbcReservationSweepAdapter implements ReservationSweepPort {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;
    private final String lockTimeout;
    private final NoShowPenaltyPolicy penalty;
    private final SensorPolicy sensor;

    JdbcReservationSweepAdapter(JdbcClient jdbc, @Value("${mspace.reservation.lock-timeout:3s}") Duration lockTimeout,
                                NoShowPenaltyPolicy penalty, SensorPolicy sensor) {
        this.jdbc = jdbc;
        this.penalty = penalty;
        this.sensor = sensor;
        this.lockTimeout = Math.max(1, lockTimeout.toMillis()) + "ms";   // 0은 PostgreSQL에서 "무한 대기"라 1ms 이상
    }

    @Override
    @Transactional
    public int markNoShows(Instant now) {
        // 끝 시각이 지난 미입실 예약도 노쇼: 지금 바로 쓰기를 14:29–14:30으로 잡으면 입실 마감(14:39)보다 끝이 먼저 와서,
        // 마감만 보면 14:39까지 BOOKED로 남아 대표자의 다음 예약을 막는다 (키패드는 끝 시각부터 이미 거절)
        // 다른 트랜잭션이 잡고 있는 줄은 건너뛰고(SKIP LOCKED) 나머지만 바꾼다. 한 줄 때문에 전체 UPDATE가 되돌려지면
        // 상관없는 사람의 지난 예약도 BOOKED로 남아 그 사람의 새 예약(대표자 1건)을 막기 때문. 건너뛴 줄은 다음 번에.
        // 키패드 체크인(BOOKED → IN_USE)과 겹치면: 체크인이 먼저 잡았으면 여기서 건너뛰고,
        // 여기가 먼저 잡았으면 체크인이 기다렸다가 WHERE(status = 'BOOKED')를 다시 보고 0줄. 둘 중 하나만 바뀐다
        List<Long> owners = withLockTimeout(() -> jdbc.sql("""
                UPDATE reservation SET status = 'NO_SHOW'
                WHERE id IN (SELECT id FROM reservation
                             WHERE status = 'BOOKED' AND (checkin_deadline < :now OR end_at <= :now)
                             FOR UPDATE SKIP LOCKED)
                  AND status = 'BOOKED'
                RETURNING owner_id
                """).param("now", Timestamp.from(now)).query(Long.class).list());
        if (!owners.isEmpty()) {
            suspendRepeatedNoShows(owners, now);
        }
        return owners.size();
    }

    @Override
    @Transactional
    public int releaseAway(Instant now) {
        // 자리 비움 (FR-DEV-103): 사용 중인데 방이 비어 있음이 15분. 타이머 시작 = 비어 있음이 된 관측 시각,
        // 끊겼다 돌아온 시각(trusted_from), 체크인 시각 중 늦은 것 (알림 AWAY_SOON과 같은 식)
        // 센서가 1분 넘게 조용하면(끊김) "있음"으로 보고 풀지 않는다 (FR-DEV-101 "틀리면 있음 쪽으로")
        // 끝 시각이 된 예약은 자동 퇴실(autoCheckout)이 맡는다. 퇴실 시각 = 15분이 찬 시각 (정리가 늦게 돌아도 같게)
        // 같은 트랜잭션에서 벌칙도 센다: 자리 비움 3번 = 노쇼 1번
        List<Long> owners = withLockTimeout(() -> jdbc.sql("""
                UPDATE reservation r
                SET status = 'AWAY_RELEASED',
                    checked_out_at = greatest(p.changed_at, p.trusted_from, r.checked_in_at) + make_interval(mins => :release)
                FROM room_presence p
                WHERE r.id IN (SELECT id FROM reservation
                               WHERE status = 'IN_USE' AND end_at > :now
                               FOR UPDATE SKIP LOCKED)
                  AND r.status = 'IN_USE'
                  AND p.room_id = r.room_id
                  AND NOT p.occupied
                  AND p.last_received_at >= :aliveAfter
                  AND greatest(p.changed_at, p.trusted_from, r.checked_in_at) <= :emptySince
                RETURNING r.owner_id
                """)
                .param("now", Timestamp.from(now))
                .param("release", sensor.away().releaseAfterMinutes())
                .param("aliveAfter", Timestamp.from(now.minusSeconds(sensor.offlineAfterSeconds())))
                .param("emptySince", Timestamp.from(now.minus(Duration.ofMinutes(sensor.away().releaseAfterMinutes()))))
                .query(Long.class).list());
        if (!owners.isEmpty()) {
            suspendRepeatedNoShows(owners, now);
        }
        return owners.size();
    }

    /**
     * 노쇼 벌칙 (FR-RSV-106): 방금 노쇼·자리 비움이 된 대표자 중 30일 안 노쇼가 2번 이상이면 지금부터 14일 정지.
     * 자리 비움(FR-DEV-103)은 3번 = 노쇼 1번으로 센다: 노쇼 1번은 3점, 자리 비움 1번은 1점, 합이 2 × 3 = 6점 이상이면 정지.
     * 바꾼 UPDATE와 같은 트랜잭션이라, 노쇼만 남고 정지가 빠지는 일은 없다 (둘 다 되거나 둘 다 안 됨).
     * <ul>
     *   <li>노쇼 시각 = 입실 마감과 끝 중 이른 쪽, 자리 비움 시각 = 퇴실 시각 (정리가 늦게 돌아도 기록 기준은 같게)</li>
     *   <li>30일은 정리가 돈 시각이 아니라 그 사람의 가장 최근 기록 시각부터 거꾸로 센다.
     *       정리가 오래 멈췄다가 돌아도 "기록끼리 30일 안"이면 정지가 걸린다</li>
     *   <li>지난 정지가 끝난 뒤의 기록만 센다 → 정지가 끝나면 처음부터 다시</li>
     *   <li>두 정리가 같은 사람의 기록을 하나씩 나눠 바꾸는 일은 없다: 대표자는 끝나지 않은 예약(BOOKED·IN_USE)이
     *       동시에 1건뿐이라(one_active_per_owner) 한 사람에게서 바뀌는 줄은 한 번에 1줄이고, 그 전 기록은 이미 커밋돼 보인다</li>
     *   <li>이미 정지 중이면 새로 걸지 않는다(NOT EXISTS). 두 정리가 동시에 돌아 둘 다 통과해도
     *       one_suspension_at_a_time(기간 겹침 금지)이 한 줄만 받고 나머지는 ON CONFLICT DO NOTHING</li>
     * </ul>
     * 대표자 기록만 센다 (참여자는 체크인 번호가 없어 노쇼 책임이 대표자에게 있음). 정지 사유는 둘 다 NO_SHOW
     */
    private void suspendRepeatedNoShows(List<Long> owners, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.sql("""
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at)
                SELECT h.owner_id, 'NO_SHOW', :now, :until
                FROM (SELECT r.owner_id,
                             CASE r.status WHEN 'NO_SHOW' THEN :perNoShow ELSE 1 END AS points,
                             CASE r.status WHEN 'NO_SHOW' THEN least(r.checkin_deadline, r.end_at)
                                           ELSE r.checked_out_at END AS happened_at
                      FROM reservation r
                      WHERE r.owner_id IN (:owners) AND r.status IN ('NO_SHOW', 'AWAY_RELEASED')) h
                WHERE h.happened_at > (SELECT max(CASE r2.status WHEN 'NO_SHOW' THEN least(r2.checkin_deadline, r2.end_at)
                                                                ELSE r2.checked_out_at END)
                                       FROM reservation r2
                                       WHERE r2.owner_id = h.owner_id AND r2.status IN ('NO_SHOW', 'AWAY_RELEASED'))
                                      - make_interval(days => :windowDays)
                  AND h.happened_at >= coalesce(
                        (SELECT max(s.ends_at) FROM user_suspension s WHERE s.user_id = h.owner_id), '-infinity'::timestamptz)
                  AND NOT EXISTS (SELECT 1 FROM user_suspension s
                                  WHERE s.user_id = h.owner_id AND s.ends_at > :now)
                GROUP BY h.owner_id
                HAVING sum(h.points) >= :points
                ON CONFLICT DO NOTHING
                """)
                .param("now", at)
                .param("owners", owners.stream().distinct().toList())
                .param("until", Timestamp.from(now.plus(Duration.ofDays(penalty.suspendDays()))))
                .param("windowDays", penalty.windowDays())
                .param("perNoShow", sensor.away().perNoShow())
                .param("points", penalty.count() * sensor.away().perNoShow())
                .update();
    }

    @Override
    @Transactional
    public int autoCheckout(Instant now) {
        // 퇴실 시각은 정리가 돈 시각이 아니라 끝나는 시각 (최대 1분 늦게 돌아도 기록은 정확하게)
        // 노쇼와 같은 이유로 잡힌 줄은 건너뛴다 (퇴실 버튼과 겹치면 하나만 바뀐다)
        return update("""
                UPDATE reservation SET status = 'AUTO_CHECKED_OUT', checked_out_at = end_at
                WHERE id IN (SELECT id FROM reservation
                             WHERE status = 'IN_USE' AND end_at <= :now
                             FOR UPDATE SKIP LOCKED)
                  AND status = 'IN_USE'
                """, now);
    }

    private int update(String sql, Instant now) {
        return withLockTimeout(() -> jdbc.sql(sql).param("now", Timestamp.from(now)).update());
    }

    private <T> T withLockTimeout(Supplier<T> work) {
        try {
            // true = 이 트랜잭션 안에서만 (커넥션 풀로 돌아가면 원래 값)
            jdbc.sql("SELECT set_config('lock_timeout', :ms, true)").param("ms", lockTimeout).query((rs, n) -> 1).list();
            return work.get();
        } catch (DataAccessException e) {
            // Spring이 55P03을 분류하지 못해서 직접 잠금 실패로 바꾼다 (JdbcReservationAdapter와 같음)
            if (LOCK_NOT_AVAILABLE.equals(sqlState(e))) {
                throw new CannotAcquireLockException("예약 정리 잠금 대기 시간 초과", e);
            }
            throw e;
        }
    }

    private static String sqlState(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getSQLState() != null) {
                return s.getSQLState();
            }
        }
        return null;
    }
}
