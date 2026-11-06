package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.reservation.application.SweepReservationsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 노쇼 벌칙 (FR-RSV-106, 11/4 지영 결정): 30일 안에 노쇼 2번이면 14일 동안 대표자로도 참여자로도 예약할 수 없다.
 * 정지가 끝나면 횟수는 처음부터. 대표자 기록만 센다.
 * 예약은 모두 1번 방 10:30–11:30(입실 마감 10:45). 날짜를 먼 미래로 둔 이유는 {@link ReservationSweepTest}와 같다.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, NoShowPenaltyTest.MovableClock.class})
class NoShowPenaltyTest {

    static class SettableClock extends Clock {
        volatile Instant now;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class MovableClock {
        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock();
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SettableClock clock;

    @Autowired
    SweepReservationsUseCase sweeps;

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, access_event, user_suspension RESTART IDENTITY CASCADE")
                .update();
        for (int i = 1; i <= 6; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
    }

    /** 2030년 날짜·서울 시각 → 시계를 옮긴다. 예: at("03-05", "10:46") */
    void at(String date, String time) {
        clock.now = OffsetDateTime.parse("2030-" + date + "T" + time + ":00+09:00").toInstant();
    }

    /** owner가 그날 10:30–11:30, 1번 방을 참여자 두 명과 잡는다 (시계는 그날 10:00으로) */
    MvcTestResult book(String date, int owner, int p1, int p2) {
        at(date, "10:00");
        return mvc.post().uri("/api/reservations").header("X-User-Id", String.valueOf(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2030-%sT10:30:00+09:00","endAt":"2030-%sT11:30:00+09:00",
                         "participantStudentIds":["2023000%d","2023000%d"]}"""
                        .formatted(date, date, p1, p2))
                .exchange();
    }

    /** 그날 대표자 1번(참여자 2·3번)이 예약하고 오지 않는다 → 10:46 정리에서 노쇼 */
    void noShow(String date) {
        assertThat(book(date, 1, 2, 3)).hasStatus(201);
        at(date, "10:46");
        sweeps.sweep();
    }

    int suspensions(int userId) {
        return jdbc.sql("SELECT count(*) FROM user_suspension WHERE user_id = :id").param("id", userId)
                .query(Integer.class).single();
    }

    @Test
    @DisplayName("노쇼 1번은 정지 없음: 바로 다시 예약할 수 있다")
    void firstNoShowIsFree() {
        noShow("03-05");

        assertThat(suspensions(1)).isZero();
        assertThat(book("03-06", 1, 2, 3)).hasStatus(201);
    }

    @Test
    @DisplayName("수용 기준: 30일 안 노쇼 2번이면 14일 정지. 대표자로 예약하면 403 USER_SUSPENDED와 끝나는 날짜")
    void secondNoShowSuspends() {
        noShow("03-05");
        noShow("03-20");

        // 정지: 03-20 10:46 ~ 04-03 10:46
        assertThat(suspensions(1)).isEqualTo(1);
        MvcTestResult result = book("03-21", 1, 2, 3);
        assertThat(result).hasStatus(403);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("USER_SUSPENDED");
        assertThat(result).bodyJson().extractingPath("$.message").asString().contains("4/3 10:46까지");
    }

    @Test
    @DisplayName("수용 기준: 정지 중인 사람은 참여자로도 못 들어간다 (403 PARTICIPANT_SUSPENDED)")
    void suspendedCannotJoin() {
        noShow("03-05");
        noShow("03-20");

        MvcTestResult result = book("03-21", 4, 1, 5);
        assertThat(result).hasStatus(403);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PARTICIPANT_SUSPENDED");
        // 정지된 사람을 빼면 된다
        assertThat(book("03-21", 4, 5, 6)).hasStatus(201);
    }

    @Test
    @DisplayName("노쇼는 대표자 기록만 센다: 같이 잡힌 참여자는 정지되지 않는다")
    void participantsAreNotCounted() {
        noShow("03-05");
        noShow("03-20");

        assertThat(suspensions(2)).isZero();
        assertThat(suspensions(3)).isZero();
        assertThat(book("03-21", 2, 3, 4)).hasStatus(201);
    }

    @Test
    @DisplayName("30일이 넘게 떨어진 노쇼 2번은 정지가 아니다")
    void outsideWindow() {
        noShow("03-01");
        noShow("04-01");   // 31일 뒤

        assertThat(suspensions(1)).isZero();
        assertThat(book("04-02", 1, 2, 3)).hasStatus(201);
    }

    @Test
    @DisplayName("정리가 늦게 돌아도 노쇼 2번이 기록끼리 30일 안이면 정지 (30일은 정리 시각이 아니라 최근 기록부터)")
    void lateSweepStillCountsByRecordTime() {
        // given: 03-05 노쇼, 03-20 예약도 오지 않았는데 정리가 멈춰 있다가 04-10에야 돈다 (03-05는 정리 시각에서 36일 전)
        noShow("03-05");
        assertThat(book("03-20", 1, 2, 3)).hasStatus(201);

        // when
        at("04-10", "10:00");
        sweeps.sweep();

        // then: 두 노쇼 시각(03-05 10:45, 03-20 10:45)은 15일 차이라 정지. 정지는 정리한 때부터 14일
        assertThat(suspensions(1)).isEqualTo(1);
        assertThat(book("04-11", 1, 2, 3)).hasStatus(403);
    }

    @Test
    @DisplayName("수용 기준: 14일이 지나면 다시 예약할 수 있고, 횟수는 처음부터라 그 뒤 노쇼 1번으로는 다시 정지되지 않는다")
    void countResetsAfterSuspension() {
        noShow("03-05");
        noShow("03-20");   // 정지 04-03 10:46까지

        assertThat(book("04-03", 1, 2, 3)).hasStatus(403);   // 04-03 10:00은 아직 정지
        noShow("04-04");   // 정지 끝난 뒤 노쇼 1번 (03-20 노쇼는 30일 안이지만 지난 정지에서 이미 셈)
        assertThat(suspensions(1)).isEqualTo(1);

        noShow("04-05");   // 정지 뒤 2번째 → 다시 정지
        assertThat(suspensions(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("신고 경고로 걸린 정지(WARNINGS)는 노쇼 횟수를 처음부터로 돌리지 않는다")
    void warningsSuspensionDoesNotResetNoShowCount() {
        noShow("03-05");
        jdbc.sql("""
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at)
                VALUES (1, 'WARNINGS', '2030-03-06T00:00:00+09:00', '2030-03-07T00:00:00+09:00')
                """).update();

        noShow("03-20");   // 03-05 노쇼와 30일 안 → 2번째로 셈

        assertThat(jdbc.sql("SELECT count(*) FROM user_suspension WHERE user_id = 1 AND reason = 'NO_SHOW'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("정리가 동시에 10번 돌아도 정지는 한 줄 (기간 겹침 금지 제약 + ON CONFLICT DO NOTHING)")
    void concurrentSweepsSuspendOnce() throws Exception {
        noShow("03-05");
        assertThat(book("03-20", 1, 2, 3)).hasStatus(201);
        at("03-20", "10:46");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            runs.add(pool.submit(() -> {
                go.await();
                return sweeps.sweep();
            }));
        }
        go.countDown();
        for (Future<?> run : runs) {
            run.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(suspensions(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 사람의 정지 기간이 겹치면 DB가 거절한다 (one_suspension_at_a_time)")
    void overlappingSuspensionRejectedByDb() {
        String insert = """
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at)
                VALUES (1, 'NO_SHOW', CAST(:s AS timestamptz), CAST(:s AS timestamptz) + interval '14 days')
                ON CONFLICT DO NOTHING
                """;
        assertThat(jdbc.sql(insert).param("s", "2030-03-01T10:00:00+09:00").update()).isEqualTo(1);
        assertThat(jdbc.sql(insert).param("s", "2030-03-10T10:00:00+09:00").update()).isZero();
        assertThat(jdbc.sql(insert).param("s", "2030-03-15T10:00:00+09:00").update()).isEqualTo(1);
    }
}
