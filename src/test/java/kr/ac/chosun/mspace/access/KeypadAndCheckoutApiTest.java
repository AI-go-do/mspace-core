package kr.ac.chosun.mspace.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.reservation.application.CheckoutReservationUseCase;
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
 * 키패드 체크인(FR-ACC-001)과 퇴실(FR-RSV-008). 시계를 테스트가 옮길 수 있게 해서
 * "시작 10분 전", "1분 잠금"을 기다리지 않고 본다. 예약은 2026-11-03(화) 10:30–11:30, 1번 방, 대표자 1번.
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KeypadAndCheckoutApiTest.MovableClock.class})
class KeypadAndCheckoutApiTest {

    /** 테스트가 지금 시각을 바꿀 수 있는 시계 */
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
    CheckoutReservationUseCase checkouts;

    String code;
    int eventSeq;

    /** 서울 시각 "10:25" → 그 시각으로 시계를 옮긴다 */
    void now(String hhmm) {
        clock.now = OffsetDateTime.parse("2026-11-03T" + hhmm + ":00+09:00").toInstant();
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
        assertThat(mvc.post().uri("/api/reservations").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2026-11-03T10:30:00+09:00","endAt":"2026-11-03T11:30:00+09:00",
                         "participantStudentIds":["20230002","20230003"]}""")
                .exchange()).hasStatus(201);
        code = jdbc.sql("SELECT access_code FROM reservation WHERE id = 1").query(String.class).single();
    }

    MvcTestResult verify(String token, String eventId, int roomId, String code) {
        var req = mvc.post().uri("/api/access/verify").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventId":"%s","roomId":%d,"deviceId":"keypad-%d","code":"%s"}"""
                        .formatted(eventId, roomId, roomId, code));
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        return req.exchange();
    }

    /** 새 eventId로 1번 방에 입력 */
    MvcTestResult press(String code) {
        return verify("test-device-token", "1-1-" + (++eventSeq), 1, code);
    }

    String wrong() {
        return code.equals("000000") ? "000001" : "000000";
    }

    /** reason이 null이면 응답에 reason 칸이 아예 없어야 한다 ({"result":"OPENED"}) */
    void assertVerdict(MvcTestResult r, String result, String reason) {
        assertThat(r).hasStatusOk().bodyJson().extractingPath("$.result").isEqualTo(result);
        if (reason == null) {
            assertThat(r).bodyJson().doesNotHavePath("$.reason");
        } else {
            assertThat(r).bodyJson().extractingPath("$.reason").isEqualTo(reason);
        }
    }

    String status() {
        return jdbc.sql("SELECT status FROM reservation WHERE id = 1").query(String.class).single();
    }

    MvcTestResult checkout(long userId) {
        return mvc.post().uri("/api/reservations/1/checkout").header("X-User-Id", String.valueOf(userId)).exchange();
    }

    @Test
    @DisplayName("수용 기준: 10:30 시작, 지금 10:25 → 열림, 상태 IN_USE. 다시 입력하면 문만 연다")
    void checkIn() {
        now("10:25");
        assertVerdict(press(code), "OPENED", null);
        assertThat(status()).isEqualTo("IN_USE");

        now("11:00");
        assertVerdict(press(code), "OPENED", null);
        assertThat(status()).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("수용 기준: 지금 10:10(시작 20분 전) → 거절 NOT_YET, 끝난 뒤 11:30 → WRONG_CODE")
    void outsideWindow() {
        now("10:19");
        assertVerdict(press(code), "DENIED", "NOT_YET");
        now("11:30");
        assertVerdict(press(code), "DENIED", "WRONG_CODE");
        assertThat(status()).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("수용 기준: 다른 방(2번) 키패드에 1번 방 번호 → 거절 WRONG_CODE")
    void otherRoom() {
        now("10:25");
        assertVerdict(verify("test-device-token", "2-1-1", 2, code), "DENIED", "WRONG_CODE");
        assertThat(status()).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("수용 기준: 5번 연속 틀리면 그 방 1분 잠금 → 맞는 번호도 LOCKED, 1분 뒤 열림")
    void lockAfterFiveFailures() {
        now("10:25");
        for (int i = 0; i < 4; i++) {
            assertVerdict(press(wrong()), "DENIED", "WRONG_CODE");
        }
        assertVerdict(press(wrong()), "DENIED", "WRONG_CODE");   // 5번째 → 잠금 시작
        assertVerdict(press(code), "DENIED", "LOCKED");
        assertVerdict(verify("test-device-token", "2-1-1", 2, "123456"), "DENIED", "WRONG_CODE"); // 다른 방은 안 잠김

        clock.now = clock.now.plusSeconds(61);
        assertVerdict(press(code), "OPENED", null);
    }

    @Test
    @DisplayName("열리면 틀린 횟수가 0으로: 4번 틀림 → 열림 → 4번 틀려도 안 잠김")
    void successResetsFailures() {
        now("10:25");
        for (int i = 0; i < 4; i++) {
            press(wrong());
        }
        assertVerdict(press(code), "OPENED", null);
        for (int i = 0; i < 4; i++) {
            press(wrong());
        }
        assertVerdict(press(code), "OPENED", null);
    }

    @Test
    @DisplayName("장치가 같은 eventId를 다시 보내면 처음 결과를 그대로, 틀린 횟수도 한 번만 센다")
    void sameEventIdOnce() {
        now("10:25");
        for (int i = 0; i < 4; i++) {
            press(wrong());
        }
        String last = "1-1-" + eventSeq;
        for (int i = 0; i < 3; i++) {    // 4번째 틀림을 세 번 더 재전송 → 잠기면 안 된다
            assertVerdict(verify("test-device-token", last, 1, wrong()), "DENIED", "WRONG_CODE");
        }
        assertVerdict(press(code), "OPENED", null);
        assertThat(jdbc.sql("SELECT count(*) FROM access_event").query(Integer.class).single()).isEqualTo(5);
    }

    @Test
    @DisplayName("장치 토큰이 없거나 틀리면 401, 상태는 그대로")
    void deviceToken() {
        now("10:25");
        assertThat(verify(null, "x-1", 1, code)).hasStatus(401).bodyJson().extractingPath("$.code")
                .isEqualTo("DEVICE_UNAUTHORIZED");
        assertThat(verify("wrong-token", "x-2", 1, code)).hasStatus(401);
        assertThat(status()).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("수용 기준: IN_USE, 지금 10:50 → 퇴실 200 CHECKED_OUT, 11:00–11:30을 다른 팀이 바로 예약 가능")
    void checkoutFreesRoom() {
        now("10:25");
        press(code);
        now("10:50");

        assertThat(checkout(1)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.id").isEqualTo(1);
            json.assertThat().extractingPath("$.status").isEqualTo("CHECKED_OUT");
        });
        assertThat(mvc.post().uri("/api/reservations").header("X-User-Id", "4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2026-11-03T11:00:00+09:00","endAt":"2026-11-03T11:30:00+09:00",
                         "participantStudentIds":["20230005","20230006"]}""")
                .exchange()).hasStatus(201);
        // 퇴실한 예약 번호로는 더 이상 안 열린다
        assertVerdict(press(code), "DENIED", "WRONG_CODE");
    }

    @Test
    @DisplayName("수용 기준: 입실 전(BOOKED) 퇴실 → 409, 참여자 퇴실 → 403, 남의 예약 → 404")
    void checkoutRules() {
        assertThat(checkout(1)).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_IN_USE");
        now("10:25");
        press(code);
        assertThat(checkout(2)).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("NOT_RESERVATION_OWNER");
        assertThat(checkout(5)).hasStatus(404);
        assertThat(status()).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("수용 기준: IN_USE에 퇴실 요청 2개 동시 → 하나만 200, 하나는 409")
    void concurrentCheckout() throws Exception {
        now("10:25");
        press(code);
        int n = 2;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        Map<String, Integer> results = new ConcurrentHashMap<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try {
                        go.await();
                        checkouts.checkout(1, 1);
                        results.merge("ok", 1, Integer::sum);
                    } catch (BusinessException e) {
                        results.merge(e.errorCode().name(), 1, Integer::sum);
                    } finally {
                        done.countDown();
                    }
                    return null;
                });
            }
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(results).isEqualTo(Map.of("ok", 1, "RESERVATION_NOT_IN_USE", 1));
        assertThat(status()).isEqualTo("CHECKED_OUT");
    }
}
