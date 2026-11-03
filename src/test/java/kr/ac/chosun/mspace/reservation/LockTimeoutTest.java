package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 잠금 대기 상한 (CodeRabbit #33 지적, 지영 결정 11/3: 최대 3초).
 * 다른 트랜잭션이 방 잠금을 쥐고 놓지 않아도, 예약 요청은 무한정 기다리지 않고 503(RESERVATION_BUSY)으로 끝난다.
 * 테스트에서는 상한을 300ms로 줄여 빨리 확인한다 (3번 다시 해도 약 1초).
 */
@SpringBootTest(properties = "mspace.reservation.lock-timeout=300ms")
@Import({TestcontainersConfiguration.class, LockTimeoutTest.FixedClock.class})
class LockTimeoutTest {

    /** "지금"을 2026-11-03 10:10(서울)으로 고정 */
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-03T01:10:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    CreateReservationUseCase service;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        jdbc.sql("""
                INSERT INTO users (email, student_id, name, verified_at)
                SELECT 'u' || i || '@chosun.ac.kr', (2024000000 + i)::text, '회원', now()
                  FROM generate_series(1, 3) i
                """).update();
    }

    @Test
    @DisplayName("다른 트랜잭션이 방 잠금을 놓지 않으면, 무한정 기다리지 않고 503(RESERVATION_BUSY)으로 끝난다")
    void givesUpWhenLockIsHeld() throws Exception {
        // given: 다른 스레드가 1번 방 잠금(-1)을 쥐고 신호가 올 때까지 놓지 않는다
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        Future<?> holding = holder.submit(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(-1)").query((rs, n) -> 1).list();
            held.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Command command = new Command(1, Instant.parse("2026-11-04T05:00:00Z"),
                    Instant.parse("2026-11-04T07:00:00Z"), List.of("2024000002", "2024000003"));

            // when
            long started = System.nanoTime();
            assertThatThrownBy(() -> service.create(1, command))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.RESERVATION_BUSY);
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            // then: 300ms × 3번 근처에서 끝났고(10초 안), 예약은 저장되지 않았다
            assertThat(waited).isLessThan(Duration.ofSeconds(10));
            assertThat(jdbc.sql("SELECT count(*) FROM reservation").query(Integer.class).single()).isZero();
        } finally {
            release.countDown();
            holding.get(10, TimeUnit.SECONDS);
            holder.shutdown();
        }
    }
}
