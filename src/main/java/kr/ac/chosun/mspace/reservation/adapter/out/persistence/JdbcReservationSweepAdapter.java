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
 * 다른 트랜잭션이 대상 줄을 잡고 오래 안 놓으면 정리가 거기서 멈추지 않게, UPDATE마다 잠금 대기 상한
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
        // 같은 줄을 키패드 체크인(BOOKED → IN_USE)이 동시에 바꾸면 줄 잠금으로 한쪽이 기다렸다가
        // WHERE를 다시 확인한다. 그래서 둘 중 하나만 바뀐다
        return update("""
                UPDATE reservation SET status = 'NO_SHOW'
                WHERE status = 'BOOKED' AND checkin_deadline < :now
                """, now);
    }

    @Override
    @Transactional
    public int autoCheckout(Instant now) {
        // 퇴실 시각은 정리가 돈 시각이 아니라 끝나는 시각 (최대 1분 늦게 돌아도 기록은 정확하게)
        return update("""
                UPDATE reservation SET status = 'AUTO_CHECKED_OUT', checked_out_at = end_at
                WHERE status = 'IN_USE' AND end_at <= :now
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
