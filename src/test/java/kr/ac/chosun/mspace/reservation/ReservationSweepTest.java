package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.reservation.application.SweepReservationsUseCase;
import kr.ac.chosun.mspace.reservation.application.SweepReservationsUseCase.Swept;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 노쇼(FR-RSV-102)와 자동 퇴실(FR-RSV-101). 시계를 옮긴 뒤 sweep()을 직접 불러 본다 (1분 스케줄러는 테스트에서 꺼 둠).
 * 예약: 2030-03-05(화) 10:30–11:30, 1번 방, 대표자 1번 + 참여자 2·3번, 체크인 마감 10:45.
 * 날짜를 먼 미래로 둔 이유: 체크인 마감은 DB가 max(시작, 예약한 시각) + 15분으로 채우는데, 예약한 시각은 진짜 시계(now())라서
 * 시작이 진짜 오늘보다 뒤여야 마감이 "시작 + 15분"으로 정해진다.
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReservationSweepTest.MovableClock.class})
class ReservationSweepTest {

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

    @Autowired
    PlatformTransactionManager tx;

    String code;
    int eventSeq;

    /** 서울 시각 "10:25" 또는 "10:45:01" → 그 시각으로 시계를 옮긴다 */
    void now(String time) {
        String t = time.length() == 5 ? time + ":00" : time;
        clock.now = OffsetDateTime.parse("2030-03-05T" + t + "+09:00").toInstant();
    }

    @BeforeEach
    void reservation() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, access_event RESTART IDENTITY CASCADE").update();
        jdbc.sql("UPDATE keypad_lock SET failed_count = 0, locked_until = NULL").update();
        for (int i = 1; i <= 6; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
        now("10:10");
        assertThat(book("10:30", "11:30")).hasStatus(201);
        code = jdbc.sql("SELECT access_code FROM reservation WHERE id = 1").query(String.class).single();
    }

    /** 대표자 1번이 참여자 2·3번과 1번 방을 잡는다 */
    MvcTestResult book(String start, String end) {
        return mvc.post().uri("/api/reservations").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2030-03-05T%s:00+09:00","endAt":"2030-03-05T%s:00+09:00",
                         "participantStudentIds":["20230002","20230003"]}""".formatted(start, end))
                .exchange();
    }

    MvcTestResult press() {
        return mvc.post().uri("/api/access/verify").header("Authorization", "Bearer test-device-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventId":"1-1-%d","roomId":1,"deviceId":"keypad-1","code":"%s"}""".formatted(++eventSeq, code))
                .exchange();
    }

    String status() {
        return jdbc.sql("SELECT status FROM reservation WHERE id = 1").query(String.class).single();
    }

    @Test
    @DisplayName("수용 기준: 체크인 마감 10:45까지는 BOOKED 그대로, 10:45를 넘기면 NO_SHOW")
    void noShowAfterDeadline() {
        now("10:45");
        assertThat(sweeps.sweep()).isEqualTo(new Swept(0, 0));
        assertThat(status()).isEqualTo("BOOKED");

        now("10:45:01");
        assertThat(sweeps.sweep()).isEqualTo(new Swept(1, 0));
        assertThat(status()).isEqualTo("NO_SHOW");
    }

    @Test
    @DisplayName("노쇼가 되면 번호도 끝: 키패드는 WRONG_CODE, 내 예약 화면에도 번호를 보여 주지 않는다")
    void noShowCodeIsDead() {
        assertThat(mvc.get().uri("/api/reservations/me").header("X-User-Id", "1").exchange())
                .bodyJson().extractingPath("$[0].accessCode").isEqualTo(code);

        now("10:46");
        sweeps.sweep();

        assertThat(press()).bodyJson().extractingPath("$.reason").isEqualTo("WRONG_CODE");
        var mine = mvc.get().uri("/api/reservations/me").header("X-User-Id", "1").exchange();
        assertThat(mine).bodyJson().extractingPath("$[0].status").isEqualTo("NO_SHOW");
        assertThat(mine).bodyJson().extractingPath("$[0].accessCode").isNull();
    }

    @Test
    @DisplayName("수용 기준: 노쇼가 되면 방·대표자·참여자가 바로 풀린다 (같은 사람들이 같은 방 11:00–12:00 → 409였다가 201)")
    void noShowReleasesRoomOwnerAndParticipants() {
        now("10:46");
        assertThat(book("11:00", "12:00")).hasStatus(409);

        sweeps.sweep();
        assertThat(book("11:00", "12:00")).hasStatus(201);
    }

    @Test
    @DisplayName("수용 기준: 입실한 예약은 11:30이 되면 AUTO_CHECKED_OUT, 퇴실 시각은 정리가 돈 시각이 아니라 11:30")
    void autoCheckoutAtEnd() {
        now("10:25");
        assertThat(press()).hasStatusOk().bodyJson().extractingPath("$.result").isEqualTo("OPENED");

        now("11:29");
        assertThat(sweeps.sweep()).isEqualTo(new Swept(0, 0));
        assertThat(status()).isEqualTo("IN_USE");

        now("11:31");
        assertThat(sweeps.sweep()).isEqualTo(new Swept(0, 1));
        assertThat(status()).isEqualTo("AUTO_CHECKED_OUT");
        assertThat(jdbc.sql("SELECT checked_out_at FROM reservation WHERE id = 1").query(OffsetDateTime.class).single()
                .toInstant()).isEqualTo(OffsetDateTime.parse("2030-03-05T11:30:00+09:00").toInstant());

        // 자동 퇴실 뒤에는 같은 사람들이 바로 다음 칸을 잡을 수 있다
        assertThat(book("12:00", "13:00")).hasStatus(201);
    }

    @Test
    @DisplayName("두 번 돌려도 한 번만 바뀐다 (서버 두 대가 같이 돌려도 같음)")
    void sweepTwice() {
        now("10:46");
        assertThat(sweeps.sweep()).isEqualTo(new Swept(1, 0));
        assertThat(sweeps.sweep()).isEqualTo(new Swept(0, 0));
        assertThat(status()).isEqualTo("NO_SHOW");
    }

    @Test
    @DisplayName("끝난 예약(퇴실·취소)은 건드리지 않는다")
    void leavesFinishedAlone() {
        now("10:25");
        press();
        assertThat(mvc.post().uri("/api/reservations/1/checkout").header("X-User-Id", "1").exchange()).hasStatusOk();

        now("12:00");
        assertThat(sweeps.sweep()).isEqualTo(new Swept(0, 0));
        assertThat(status()).isEqualTo("CHECKED_OUT");
    }

    @Test
    @DisplayName("수용 기준: 입실 마감 10:45까지는 키패드로 입실, 10:45를 넘기면 정리 전이라도 바로 WRONG_CODE")
    void keypadClosesAtDeadline() {
        now("10:45:01");
        assertThat(press()).bodyJson().extractingPath("$.reason").isEqualTo("WRONG_CODE");
        assertThat(status()).isEqualTo("BOOKED");   // 상태는 다음 정리에서 바뀐다
        assertThat(sweeps.sweep()).isEqualTo(new Swept(1, 0));
    }

    @Test
    @DisplayName("마감 10:45 정각까지는 열리고, 입실한 사람은 마감 뒤에도 끝나기 전까지 다시 열 수 있다")
    void deadlineIsInclusiveAndReentryStillOpens() {
        now("10:45");
        assertThat(press()).bodyJson().extractingPath("$.result").isEqualTo("OPENED");
        assertThat(status()).isEqualTo("IN_USE");

        now("11:10");
        assertThat(press()).bodyJson().extractingPath("$.result").isEqualTo("OPENED");
        assertThat(sweeps.sweep()).isEqualTo(new Swept(0, 0));
    }

    @Test
    @DisplayName("다른 작업이 예약 줄 하나를 오래 잡고 있어도 정리는 그 줄만 건너뛰고 다른 노쇼·자동 퇴실은 바로 처리한다")
    void lockedRowDoesNotBlockSweep() throws Exception {
        // 2번 방: 대표자 4번 + 5·6번, 10:30–11:00, 입실함
        assertThat(mvc.post().uri("/api/reservations").header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":2,"startAt":"2030-03-05T10:30:00+09:00","endAt":"2030-03-05T11:00:00+09:00",
                         "participantStudentIds":["20230005","20230006"]}""").exchange()).hasStatus(201);
        String code2 = jdbc.sql("SELECT access_code FROM reservation WHERE id = 2").query(String.class).single();
        now("10:25");
        assertThat(mvc.post().uri("/api/access/verify").header("Authorization", "Bearer test-device-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventId":"2-1-1","roomId":2,"deviceId":"keypad-2","code":"%s"}""".formatted(code2))
                .exchange()).bodyJson().extractingPath("$.result").isEqualTo("OPENED");

        // 3번 방: 대표자 7번 + 8·9번, 10:30–11:00, 안 들어옴 (1번과 상관없는 사람의 노쇼 대상)
        for (int i = 7; i <= 9; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
        assertThat(mvc.post().uri("/api/reservations").header("X-User-Id", "7").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":3,"startAt":"2030-03-05T10:30:00+09:00","endAt":"2030-03-05T11:00:00+09:00",
                         "participantStudentIds":["20230008","20230009"]}""").exchange()).hasStatus(201);

        // 다른 트랜잭션이 1번 예약 줄을 6초 동안 잡고 있다
        CountDownLatch held = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = other.submit(() -> new TransactionTemplate(tx).executeWithoutResult(s -> {
                jdbc.sql("SELECT id FROM reservation WHERE id = 1 FOR UPDATE").query(Long.class).single();
                held.countDown();
                try {
                    Thread.sleep(6000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

            now("11:01");   // 1번은 노쇼 대상(잠김), 3번도 노쇼 대상(안 잠김), 2번은 자동 퇴실 대상
            Swept swept = sweeps.sweep();

            // 잠긴 줄을 기다렸다면 노쇼 단계가 잠금 대기 상한(3초)에 걸려 Swept(0, 1)이 된다. 1이면 건너뛰고 처리한 것
            assertThat(swept).isEqualTo(new Swept(1, 1));
            assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = 3").query(String.class).single())
                    .isEqualTo("NO_SHOW");
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }
        assertThat(status()).isEqualTo("BOOKED");
        // 잠금이 풀린 뒤 다음 번에는 처리된다
        assertThat(sweeps.sweep()).isEqualTo(new Swept(1, 0));
        assertThat(status()).isEqualTo("NO_SHOW");
    }
}
