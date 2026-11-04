package kr.ac.chosun.mspace.reservation;

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
 * 연장하기(FR-RSV-105). 날짜는 2030-03-05(화), 1번 방(3–5명).
 * 대표자 1번 + 2·3번(우리 팀), 대표자 4번 + 5·6번(다른 팀). 우리 팀은 13:00–14:00을 잡고 12:55에 입실해 둔다.
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ContinueReservationApiTest.MovableClock.class})
class ContinueReservationApiTest {

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

    int eventSeq;

    static Instant at(String time) {
        String t = time.length() == 5 ? time + ":00" : time;
        return OffsetDateTime.parse("2030-03-05T" + t + "+09:00").toInstant();
    }

    void now(String time) {
        clock.now = at(time);
    }

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, access_event RESTART IDENTITY CASCADE").update();
        jdbc.sql("UPDATE keypad_lock SET failed_count = 0, locked_until = NULL").update();
        for (int i = 1; i <= 6; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
        inUse();
    }

    /** 미리 예약. owner 1이면 2·3번과, 4면 5·6번과 */
    MvcTestResult book(int owner, String start, String end) {
        String others = owner == 1 ? "\"20230002\",\"20230003\"" : "\"20230005\",\"20230006\"";
        return mvc.post().uri("/api/reservations").header("X-User-Id", String.valueOf(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2030-03-05T%s:00+09:00","endAt":"2030-03-05T%s:00+09:00",
                         "participantStudentIds":[%s]}""".formatted(start, end, others))
                .exchange();
    }

    MvcTestResult press(long reservationId) {
        String code = jdbc.sql("SELECT access_code FROM reservation WHERE id = :id").param("id", reservationId)
                .query(String.class).single();
        return mvc.post().uri("/api/access/verify").header("Authorization", "Bearer test-device-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventId":"c-%d","roomId":1,"deviceId":"keypad-1","code":"%s"}""".formatted(++eventSeq, code))
                .exchange();
    }

    String status(long id) {
        return jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", id).query(String.class).single();
    }

    long ours;

    void inUse() {
        now("12:00");
        ours = id(book(1, "13:00", "14:00"));
        now("12:55");
        assertThat(press(ours)).bodyJson().extractingPath("$.result").isEqualTo("OPENED");
        assertThat(status(ours)).isEqualTo("IN_USE");
    }

    static long id(MvcTestResult r) {
        assertThat(r).hasStatus(201);
        return Long.parseLong(r.getResponse().getHeader("Location").replaceAll(".*/", ""));
    }

    MvcTestResult extend(long id, String end) {
        return extend(1, id, end, null);
    }

    MvcTestResult extend(int user, long id, String end, String key) {
        var req = mvc.post().uri("/api/reservations/" + id + "/continue").header("X-User-Id", String.valueOf(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"endAt":"2030-03-05T%s:00+09:00"}""".formatted(end));
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return req.exchange();
    }

    String code(long id) {
        return jdbc.sql("SELECT access_code FROM reservation WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    @DisplayName("수용 기준: 13:52에 15:00까지 연장하면 지금 예약은 퇴실, 새 예약은 13:52부터 바로 사용 중이고 새 번호가 나온다")
    void continuesNow() {
        // given
        now("13:52:30");

        // when
        MvcTestResult r = extend(ours, "15:00");

        // then
        long next = id(r);
        assertThat(r).bodyJson().extractingPath("$.status").isEqualTo("IN_USE");
        assertThat(r).bodyJson().extractingPath("$.startAt").isEqualTo("2030-03-05T13:52:30+09:00");
        assertThat(r).bodyJson().extractingPath("$.headcount").isEqualTo(3);
        assertThat(status(ours)).isEqualTo("CHECKED_OUT");
        assertThat(status(next)).isEqualTo("IN_USE");
        assertThat(jdbc.sql("SELECT continued_from_id FROM reservation WHERE id = :id").param("id", next)
                .query(Long.class).single()).isEqualTo(ours);
        assertThat(code(next)).isNotEqualTo(code(ours));
        assertThat(jdbc.sql("SELECT count(*) FROM reservation_participant WHERE reservation_id = :id AND active")
                .param("id", next).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    @DisplayName("예전 번호는 연장하면 바로 무효, 새 번호로는 다시 들어올 수 있다")
    void oldCodeDies() {
        now("13:55");
        String old = code(ours);
        long next = id(extend(ours, "15:00"));

        assertThat(mvc.post().uri("/api/access/verify").header("Authorization", "Bearer test-device-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventId":"c-old","roomId":1,"deviceId":"keypad-1","code":"%s"}""".formatted(old))
                .exchange()).bodyJson().extractingPath("$.result").isEqualTo("DENIED");
        assertThat(press(next)).bodyJson().extractingPath("$.result").isEqualTo("OPENED");
    }

    @Test
    @DisplayName("종료 10분 전보다 이르거나 종료 시각이 지나면 409 RESERVATION_EXTENSION_NOT_OPEN")
    void onlyInLastTenMinutes() {
        now("13:49:59");
        assertThat(extend(ours, "15:00")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RESERVATION_EXTENSION_NOT_OPEN");
        now("14:00");
        assertThat(extend(ours, "15:00")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RESERVATION_EXTENSION_NOT_OPEN");
        assertThat(status(ours)).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("끝은 지금 종료 + 30분 단위, 최대 2시간: 14:45·16:30·14:00은 400, 16:00은 된다")
    void endRules() {
        now("13:50");
        for (String bad : new String[] {"14:45", "16:30", "14:00", "13:30"}) {
            assertThat(extend(ours, bad)).hasStatus(400).bodyJson().extractingPath("$.code")
                    .isEqualTo("RESERVATION_INVALID_LENGTH");
        }
        assertThat(extend(ours, "16:00")).hasStatus(201);
    }

    @Test
    @DisplayName("끝에 소수 초가 붙으면(14:30:00.4) 30분 칸이 아니라 400 RESERVATION_INVALID_LENGTH")
    void fractionalSecondEndRejected() {
        now("13:50");

        MvcTestResult r = mvc.post().uri("/api/reservations/" + ours + "/continue").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"endAt":"2030-03-05T14:30:00.4+09:00"}""")
                .exchange();

        assertThat(r).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_INVALID_LENGTH");
        assertThat(status(ours)).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("다음 칸을 다른 팀이 잡았으면 409 RESERVATION_TIME_TAKEN이고 지금 예약은 그대로 사용 중")
    void nextSlotTaken() {
        now("12:56");
        id(book(4, "14:00", "15:00"));
        now("13:55");

        assertThat(extend(ours, "15:00")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RESERVATION_TIME_TAKEN");
        assertThat(status(ours)).isEqualTo("IN_USE");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation WHERE continued_from_id IS NOT NULL")
                .query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("수용 기준: 연장은 1회만. 연장한 예약을 또 연장하면 409 RESERVATION_EXTENSION_LIMIT")
    void onlyOnce() {
        now("13:55");
        long next = id(extend(ours, "15:00"));
        now("14:55");

        assertThat(extend(next, "16:00")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RESERVATION_EXTENSION_LIMIT");
        assertThat(status(next)).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("우회 막기: 연장까지 쓴 뒤 30분 안에 시작하는 새 예약은 409, 30분이 지나면 된다")
    void noBypassByNewBooking() {
        now("13:55");
        id(extend(ours, "15:00"));
        now("15:10");   // 15:00에 자동 퇴실 (만들기 직전 정리)

        assertThat(book(1, "15:30", "16:30")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RESERVATION_EXTENSION_LIMIT");
        assertThat(book(1, "16:00", "17:00")).hasStatus(201);
    }

    @Test
    @DisplayName("연장 버튼을 동시에 10번 눌러도 새 예약은 하나만 생기고 나머지는 409")
    void concurrentPresses() throws Exception {
        now("13:55");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(10);
        try {
            var ready = new java.util.concurrent.CountDownLatch(1);
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    ready.await();
                    return extend(ours, "15:00").getResponse().getStatus();
                }));
            }
            ready.countDown();
            var codes = new java.util.ArrayList<Integer>();
            for (var f : results) {
                codes.add(f.get());
            }
            assertThat(codes).filteredOn(c -> c == 201).hasSize(1);
            assertThat(codes).filteredOn(c -> c == 409).hasSize(9);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM reservation WHERE continued_from_id = :id").param("id", ours)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("연장 없이 끝나고 다시 잡은 예약은 연장할 수 있다 (연장은 이어진 사용에 1회)")
    void plainChainCanExtendOnce() {
        now("14:01");   // 14:00 자동 퇴실
        long second = id(book(1, "14:30", "15:30"));
        now("14:25");
        assertThat(press(second)).bodyJson().extractingPath("$.result").isEqualTo("OPENED");
        now("15:25");

        assertThat(extend(second, "16:00")).hasStatus(201);
    }

    @Test
    @DisplayName("참여자는 403, 남은 404")
    void ownerOnly() {
        now("13:55");
        assertThat(extend(2, ours, "15:00", null)).hasStatus(403);
        assertThat(extend(4, ours, "15:00", null)).hasStatus(404);
        assertThat(extend(1, 999, "15:00", null)).hasStatus(404);
        assertThat(status(ours)).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("입실 전(BOOKED) 예약은 연장할 수 없다 409 RESERVATION_NOT_IN_USE")
    void notInUse() {
        now("13:00");
        jdbc.sql("UPDATE reservation SET status = 'CHECKED_OUT', checked_out_at = now() WHERE id = :id")
                .param("id", ours).update();
        now("14:01");
        long later = id(book(1, "15:00", "16:00"));
        now("15:55");
        assertThat(extend(later, "16:30")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RESERVATION_NOT_IN_USE");
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 다시 보내면 처음 연장을 200, 다른 끝 시각에 쓰면 409")
    void idempotent() {
        now("13:55");
        MvcTestResult first = extend(1, ours, "15:00", "k-1");
        long next = id(first);

        MvcTestResult again = extend(1, ours, "15:00", "k-1");
        assertThat(again).hasStatus(200).bodyJson().extractingPath("$.id").isEqualTo((int) next);
        assertThat(extend(1, ours, "15:30", "k-1")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("비서가 보낸 연장은 created_via = AGENT")
    void agent() {
        now("13:55");
        long next = id(mvc.post().uri("/api/reservations/" + ours + "/continue").header("X-User-Id", "1")
                .header("X-Created-Via", "AGENT").contentType(MediaType.APPLICATION_JSON)
                .content("{\"endAt\":\"2030-03-05T15:00:00+09:00\"}").exchange());
        assertThat(jdbc.sql("SELECT created_via FROM reservation WHERE id = :id").param("id", next)
                .query(String.class).single()).isEqualTo("AGENT");
    }
}
