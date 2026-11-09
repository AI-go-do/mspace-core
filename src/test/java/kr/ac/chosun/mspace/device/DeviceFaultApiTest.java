package kr.ac.chosun.mspace.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * 방의 사과 (Q-19): 문 장치 오류로 못 들어가 생긴 노쇼를 무른다. 날짜는 2030-03-05(화), 1번 방(3–5명).
 * 대표자 1번 + 2·3번. 노쇼 마감은 시작 + 15분
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DeviceFaultApiTest.MovableClock.class})
class DeviceFaultApiTest {

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

    static Instant at(String time) {
        return OffsetDateTime.parse("2030-03-05T" + time + ":00+09:00").toInstant();
    }

    void now(String time) {
        clock.now = at(time);
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE device_fault_attempt, device_fault, user_suspension, notification, reservation,
                         reservation_participant, users RESTART IDENTITY CASCADE
                """).update();
        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "f" + i + "@chosun.ac.kr").param("s", "2023200" + i).update();
        }
    }

    /** 1번 팀이 1번 방을 start–end 예약. 예약 id */
    long book(String start, String end) {
        MvcTestResult r = mvc.post().uri("/api/reservations").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2030-03-05T%s:00+09:00","endAt":"2030-03-05T%s:00+09:00",
                         "participantStudentIds":["20232002","20232003"]}""".formatted(start, end))
                .exchange();
        assertThat(r).hasStatus(201);
        return jdbc.sql("SELECT max(id) FROM reservation").query((rs, n) -> rs.getLong(1)).single();
    }

    String code(long reservationId) {
        return jdbc.sql("SELECT access_code FROM reservation WHERE id = :id").param("id", reservationId)
                .query((rs, n) -> rs.getString(1)).single();
    }

    MvcTestResult fault(String eventId, String start, String end, String attempts) {
        return mvc.post().uri("/api/device-faults").header("Authorization", "Bearer test-device-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventId":"%s","roomId":1,"deviceId":"qr-1","kind":"SERVER_UNREACHABLE",
                         "startedAt":"2030-03-05T%s:00+09:00","endedAt":"2030-03-05T%s:00+09:00","attempts":[%s]}"""
                        .formatted(eventId, start, end, attempts))
                .exchange();
    }

    static String attempt(String time, String code) {
        return """
                {"at":"2030-03-05T%s:00+09:00","code":"%s","via":"QR"}""".formatted(time, code);
    }

    /** 응답과 따로 도는 무르기를 최대 5초 기다린다 */
    static boolean eventually(java.util.function.BooleanSupplier check) {
        for (int i = 0; i < 100; i++) {
            if (check.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return check.getAsBoolean();
    }

    boolean excused(long reservationId) {
        return jdbc.sql("SELECT no_show_excused_at IS NOT NULL FROM reservation WHERE id = :id")
                .param("id", reservationId).query((rs, n) -> rs.getBoolean(1)).single();
    }

    long apologies() {
        return jdbc.sql("SELECT count(*) FROM notification WHERE kind = 'DEVICE_APOLOGY'")
                .query((rs, n) -> rs.getLong(1)).single();
    }

    @Test
    @DisplayName("노쇼가 된 뒤 장치 오류가 오면, 그 구간에 번호가 눌린 예약의 노쇼를 무르고 세 명에게 사과 알림. 같은 eventId는 한 번만")
    void excusesAfterNoShow() {
        now("09:00");
        long id = book("10:00", "11:00");
        now("10:20");
        sweeps.sweep();

        MvcTestResult r = fault("qr-1-1", "09:55", "10:20", attempt("10:02", code(id)));

        assertThat(r).hasStatus(200).bodyJson().extractingPath("$.result").isEqualTo("STORED");
        assertThat(r).bodyJson().doesNotHavePath("$.matched").doesNotHavePath("$.excused");
        // 요청은 저장만 하고, 무르기는 응답과 따로 바로 돈다 (1분 정리를 기다리지 않음)
        assertThat(eventually(() -> excused(id))).as("몇 초 안에 무름").isTrue();
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", id).query(String.class)
                .single()).as("상태는 노쇼 그대로 (기록)").isEqualTo("NO_SHOW");
        assertThat(apologies()).isEqualTo(3);

        assertThat(fault("qr-1-1", "09:55", "10:20", attempt("10:02", code(id))))
                .bodyJson().extractingPath("$.result").isEqualTo("DUPLICATE");
        assertThat(apologies()).isEqualTo(3);
    }

    @Test
    @DisplayName("오류가 노쇼 처리보다 먼저 오면 1분 정리가 노쇼로 바꾼 뒤 바로 무른다")
    void excusesWhenFaultArrivesFirst() {
        now("09:00");
        long id = book("10:00", "11:00");
        now("10:12");

        assertThat(fault("qr-1-2", "10:00", "10:10", attempt("10:05", code(id)))).hasStatus(200);
        assertThat(excused(id)).as("아직 노쇼 전").isFalse();
        now("10:20");
        sweeps.sweep();

        assertThat(excused(id)).isTrue();
        assertThat(apologies()).isEqualTo(3);
    }

    @Test
    @DisplayName("무른 노쇼 때문에 걸린 정지는 풀리고, 무른 노쇼는 다음 정지 셈에서 빠진다")
    void liftsSuspension() {
        now("07:00");
        long first = book("08:00", "09:00");
        now("08:20");
        sweeps.sweep();
        long second = book("10:00", "11:00");
        now("10:20");
        sweeps.sweep();
        assertThat(jdbc.sql("SELECT count(*) FROM user_suspension WHERE user_id = 1").query(Long.class).single())
                .as("30일 안 노쇼 2번 → 정지").isEqualTo(1);
        now("10:25");

        fault("qr-1-3", "10:00", "10:20", attempt("10:03", code(second)));
        sweeps.sweep();

        assertThat(excused(first)).isFalse();
        assertThat(excused(second)).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM user_suspension WHERE user_id = 1 AND ends_at > :now")
                .param("now", java.sql.Timestamp.from(clock.now)).query(Long.class).single())
                .as("정지가 지금 끝남").isZero();
        assertThat(jdbc.sql("SELECT body FROM notification WHERE kind = 'DEVICE_APOLOGY' AND user_id = 1")
                .query(String.class).single()).contains("이용 정지도 풀었어요");
    }

    void suspend(String from, String until) {
        jdbc.sql("INSERT INTO user_suspension (user_id, reason, starts_at, ends_at) VALUES (1, 'NO_SHOW', :f, :u)")
                .param("f", java.sql.Timestamp.from(at(from))).param("u", java.sql.Timestamp.from(at(until))).update();
    }

    long activeSuspensions() {
        return jdbc.sql("SELECT count(*) FROM user_suspension WHERE user_id = 1 AND ends_at > :now")
                .param("now", java.sql.Timestamp.from(clock.now)).query(Long.class).single();
    }

    @Test
    @DisplayName("이 노쇼를 센 정지가 이미 끝났으면, 그 뒤 다른 노쇼로 걸린 새 정지는 풀지 않는다")
    void keepsLaterSuspension() {
        now("09:00");
        long id = book("10:00", "11:00");
        now("10:16");
        sweeps.sweep();
        suspend("10:16", "10:18"); // 이 노쇼를 센 정지(이미 끝남)
        suspend("10:19", "23:00"); // 그 뒤 다른 노쇼들로 걸린 정지
        now("10:25");

        fault("qr-1-7", "10:00", "10:20", attempt("10:03", code(id)));
        sweeps.sweep();

        assertThat(excused(id)).isTrue();
        assertThat(activeSuspensions()).as("상관없는 새 정지는 그대로").isEqualTo(1);
    }

    @Test
    @DisplayName("정지 기간 안에 생긴 노쇼는 어느 정지에도 세지 않았으므로, 무르더라도 다음 정지를 풀지 않는다")
    void keepsSuspensionNotCountingIt() {
        now("09:00");
        long id = book("10:00", "11:00");
        suspend("09:30", "10:30"); // 노쇼(10:15)가 이 정지 안에 생김
        now("10:16");
        sweeps.sweep();
        suspend("10:31", "23:00");
        now("10:40");

        fault("qr-1-8", "10:00", "10:20", attempt("10:03", code(id)));
        sweeps.sweep();

        assertThat(excused(id)).isTrue();
        assertThat(activeSuspensions()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 번호이거나 입실 마감 뒤에 눌렀으면 예약을 찾지 않고 무르지 않는다. 번호는 저장하지 않는다")
    void ignoresWrongCodeOrLateAttempt() {
        now("09:00");
        long id = book("10:00", "11:00");
        String wrong = code(id).equals("000000") ? "111111" : "000000";
        now("10:30");
        sweeps.sweep();

        MvcTestResult r = fault("qr-1-4", "10:00", "10:30",
                attempt("10:05", wrong) + "," + attempt("10:20", code(id)));

        assertThat(r).hasStatus(200).bodyJson().extractingPath("$.result").isEqualTo("STORED");
        assertThat(excused(id)).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM device_fault_attempt WHERE reservation_id IS NULL")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'device_fault_attempt' AND column_name = 'code'""").query(Long.class).single())
                .isZero();
    }

    @Test
    @DisplayName("번호 시각이 오류 구간 밖이거나 끝이 시작보다 앞이면 400, 장치 토큰이 없으면 401")
    void rejectsBadRequests() {
        now("10:30");
        assertThat(fault("qr-1-5", "10:00", "10:10", attempt("10:20", "123456"))).hasStatus(400);
        assertThat(fault("qr-1-6", "10:10", "10:00", "")).hasStatus(400);
        assertThat(fault("qr-1-7", "10:00", "10:10", attempt("10:05", "12ab56"))).hasStatus(400);
        assertThat(mvc.post().uri("/api/device-faults").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"x\",\"roomId\":1}").exchange()).hasStatus(401);
    }

    @Test
    @DisplayName("방마다 하루에 받는 번호는 200개까지, 넘으면 429 (토큰이 새도 번호를 끝없이 넣어 보지 못하게)")
    void limitsAttemptsPerRoomPerDay() {
        now("10:30");
        String hundred = String.join(",", java.util.Collections.nCopies(100, attempt("10:05", "123456")));

        assertThat(fault("qr-1-l1", "10:00", "10:10", hundred)).hasStatus(200);
        assertThat(fault("qr-1-l2", "10:00", "10:10", hundred)).hasStatus(200);
        assertThat(fault("qr-1-l3", "10:00", "10:10", attempt("10:05", "123456"))).hasStatus(429)
                .bodyJson().extractingPath("$.code").isEqualTo("DEVICE_FAULT_TOO_MANY");
        assertThat(fault("qr-1-l4", "10:00", "10:10", "")).as("번호 없는 오류 기록은 받는다").hasStatus(200);
    }
}
