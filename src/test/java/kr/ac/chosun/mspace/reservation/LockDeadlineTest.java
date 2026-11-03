package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort.NewReservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 잠금 대기 상한은 잠금 하나가 아니라 "한 번의 저장 시도 전체"에 걸린다 (CodeRabbit #36 지적).
 * PostgreSQL lock_timeout은 잠금 하나마다 적용돼서, 방 잠금을 1.5초 기다린 뒤 회원 잠금을 또 2초 기다리면
 * 한 번에 3.5초가 걸릴 수 있다. 남은 시간만 다시 걸면 2초 근처에서 끝나야 한다.
 */
@SpringBootTest(properties = "mspace.reservation.lock-timeout=2s")
@Import(TestcontainersConfiguration.class)
class LockDeadlineTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ReservationCommandPort port;

    /** 다른 트랜잭션에서 key 잠금을 잡고 release 신호(또는 holdMs)까지 쥐고 있는다 */
    Future<?> hold(ExecutorService pool, long key, CountDownLatch held, CountDownLatch release, long holdMs) {
        return pool.submit(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", key).query((rs, n) -> 1).list();
            held.countDown();
            try {
                release.await(holdMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    @Test
    @DisplayName("방 잠금을 1.5초 기다린 뒤 회원 잠금이 막혀도, 한 번의 저장 시도는 상한 2초 근처에서 끝난다")
    void deadlineCoversAllLocks() throws Exception {
        // given: 1번 방 잠금(-1)은 1.5초 뒤 풀리고, 1번 회원 잠금(1)은 끝까지 안 풀린다
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> room = hold(pool, -1, held, new CountDownLatch(1), 1500);
        Future<?> member = hold(pool, 1, held, release, 30_000);
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            NewReservation r = new NewReservation(1, 1, Instant.parse("2026-11-04T05:00:00Z"),
                    Instant.parse("2026-11-04T07:00:00Z"), "123456", List.of(1L, 2L, 3L), null);

            // when: 다시 하기 없이 저장 한 번만
            long started = System.nanoTime();
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> port.insert(r)))
                    .isInstanceOf(CannotAcquireLockException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            // then: 잠금마다 2초씩이면 1.5 + 2 = 3.5초. 남은 시간만 걸면 약 2초
            assertThat(waited).isLessThan(Duration.ofMillis(2800));
        } finally {
            release.countDown();
            room.get(10, TimeUnit.SECONDS);
            member.get(10, TimeUnit.SECONDS);
            pool.shutdown();
        }
    }
}
