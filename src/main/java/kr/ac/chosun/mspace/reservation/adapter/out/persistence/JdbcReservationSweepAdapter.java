package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

    JdbcReservationSweepAdapter(JdbcClient jdbc, @Value("${mspace.reservation.lock-timeout:3s}") Duration lockTimeout) {
        this.jdbc = jdbc;
        this.lockTimeout = Math.max(1, lockTimeout.toMillis()) + "ms";   // 0은 PostgreSQL에서 "무한 대기"라 1ms 이상
    }

    @Override
    @Transactional
    public int markNoShows(Instant now) {
        // 다른 트랜잭션이 잡고 있는 줄은 건너뛰고(SKIP LOCKED) 나머지만 바꾼다. 한 줄 때문에 전체 UPDATE가 되돌려지면
        // 상관없는 사람의 지난 예약도 BOOKED로 남아 그 사람의 새 예약(대표자 1건)을 막기 때문. 건너뛴 줄은 다음 번에.
        // 키패드 체크인(BOOKED → IN_USE)과 겹치면: 체크인이 먼저 잡았으면 여기서 건너뛰고,
        // 여기가 먼저 잡았으면 체크인이 기다렸다가 WHERE(status = 'BOOKED')를 다시 보고 0줄. 둘 중 하나만 바뀐다
        return update("""
                UPDATE reservation SET status = 'NO_SHOW'
                WHERE id IN (SELECT id FROM reservation
                             WHERE status = 'BOOKED' AND checkin_deadline < :now
                             FOR UPDATE SKIP LOCKED)
                  AND status = 'BOOKED'
                """, now);
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
        try {
            // true = 이 트랜잭션 안에서만 (커넥션 풀로 돌아가면 원래 값)
            jdbc.sql("SELECT set_config('lock_timeout', :ms, true)").param("ms", lockTimeout).query((rs, n) -> 1).list();
            return jdbc.sql(sql).param("now", Timestamp.from(now)).update();
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
