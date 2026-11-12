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
 * 빈방 릴레이 (Q-18). 날짜는 2030-03-05(화), 1번 방(3–5명).
 * 팀 A = 대표자 1 + 2·3, 팀 B = 4 + 5·6, 팀 C = 7 + 8·9, 팀 D = 10 + 11·12. 팀 A가 14:00–15:00을 먼저 잡아 둔다.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RelayApiTest.MovableClock.class})
class RelayApiTest {

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
    kr.ac.chosun.mspace.reservation.application.RelayUseCase relays;

    @Autowired
    javax.sql.DataSource dataSource;

    long teamA;

    static Instant at(String time) {
        String t = time.length() == 5 ? time + ":00" : time;
        return OffsetDateTime.parse("2030-03-05T" + t + "+09:00").toInstant();
    }

    void now(String time) {
        clock.now = at(time);
    }

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE relay_offer, relay_wait, notification, reservation, reservation_participant, users "
                + "RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 12; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "%08d".formatted(20230000 + i)).update();
        }
        now("10:00");
        teamA = id(book(1, "14:00", "15:00", null));
    }

    /** 미리 예약. 대표자 owner + 그다음 두 사람 */
    MvcTestResult book(int owner, String start, String end, Long offerId) {
        String others = "\"%08d\",\"%08d\"".formatted(20230000 + owner + 1, 20230000 + owner + 2);
        String offer = offerId == null ? "" : ",\"relayOfferId\":" + offerId;
        return mvc.post().uri("/api/reservations").header("X-User-Id", String.valueOf(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2030-03-05T%s:00+09:00","endAt":"2030-03-05T%s:00+09:00",
                         "participantStudentIds":[%s]%s}""".formatted(start, end, others, offer))
                .exchange();
    }

    MvcTestResult waitFor(int user, int room, String start) {
        return mvc.post().uri("/api/relay/waits").header("X-User-Id", String.valueOf(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":%d,"startAt":"2030-03-05T%s:00+09:00"}""".formatted(room, start))
                .exchange();
    }

    MvcTestResult cancel(int owner, long id) {
        return mvc.post().uri("/api/reservations/{id}/cancel", id).header("X-User-Id", String.valueOf(owner)).exchange();
    }

    MvcTestResult offers(int user) {
        return mvc.get().uri("/api/relay/offers/me").header("X-User-Id", String.valueOf(user)).exchange();
    }

    long offerId(int user) {
        return jdbc.sql("SELECT id FROM relay_offer WHERE user_id = :u AND status = 'OPEN'").param("u", user)
                .query(Long.class).single();
    }

    String waitStatus(int user) {
        return jdbc.sql("SELECT status FROM relay_wait WHERE user_id = :u ORDER BY id LIMIT 1").param("u", user)
                .query(String.class).single();
    }

    static long id(MvcTestResult r) {
        assertThat(r).hasStatus(201);
        return Long.parseLong(r.getResponse().getHeader("Location").replaceAll(".*/", ""));
    }

    @Test
    @DisplayName("취소되면 먼저 기다린 사람에게 5분 넘기고, 그동안 다른 사람은 409, 넘겨받은 사람은 예약된다")
    void offerToFirstAndTake() {
        // given: 팀 B가 14:00 칸, 팀 C가 14:30 칸을 기다린다 (B가 먼저)
        assertThat(waitFor(4, 1, "14:00")).hasStatusOk().bodyJson().extractingPath("$.order").isEqualTo(1);
        now("10:01");
        assertThat(waitFor(7, 1, "14:30")).hasStatusOk();

        // when: 팀 A가 취소
        now("10:10");
        assertThat(cancel(1, teamA)).hasStatusOk();

        // then: 팀 B에게 14:00–15:00이 10:15까지 넘어오고 알림이 간다
        assertThat(offers(4)).hasStatusOk().bodyJson().extractingPath("$[0].expiresAt")
                .isEqualTo("2030-03-05T10:15:00+09:00");
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE user_id = 4 AND kind = 'RELAY_OFFER'")
                .query(Integer.class).single()).isEqualTo(1);
        // 팀 C도, 아무도 안 기다린 팀 D도 그 구간은 못 잡는다
        assertThat(book(7, "14:30", "15:00", null)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RELAY_HELD");
        assertThat(book(10, "14:00", "14:30", null)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RELAY_HELD");

        // when: 팀 B가 받기 (보통 예약 + relayOfferId)
        now("10:12");
        long taken = id(book(4, "14:00", "15:00", offerId(4)));

        // then
        assertThat(jdbc.sql("SELECT reservation_id FROM relay_offer WHERE user_id = 4").query(Long.class).single())
                .isEqualTo(taken);
        assertThat(waitStatus(4)).isEqualTo("TAKEN");
        assertThat(offers(4)).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("취소 뒤 첫 넘기기가 실패해도 1분 정리가 줄 첫 사람에게 넘긴다")
    void sweepRetriesFailedRelease() {
        // given: B가 14:00 칸을 기다리는데, 팀 A 취소 뒤 첫 넘기기가 실패했다 (넘김이 없고 B는 그대로 줄에 있음)
        waitFor(4, 1, "14:00");
        now("10:10");
        cancel(1, teamA);
        jdbc.sql("DELETE FROM notification").update();
        jdbc.sql("DELETE FROM relay_offer").update();
        jdbc.sql("UPDATE relay_wait SET status = 'WAITING' WHERE user_id = 4").update();

        // when: 1분 정리
        now("10:11");
        sweeps.sweep();

        // then: B에게 10:16까지 넘어오고 알림도 간다. 한 번 더 돌아도 또 만들지 않는다
        assertThat(offers(4)).hasStatusOk().bodyJson().extractingPath("$[0].expiresAt")
                .isEqualTo("2030-03-05T10:16:00+09:00");
        sweeps.sweep();
        assertThat(jdbc.sql("SELECT count(*) FROM relay_offer").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE user_id = 4 AND kind = 'RELAY_OFFER'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("넘겨받은 사람이 일부만 받으면 남은 칸은 그 칸을 기다린 다음 사람에게 넘어간다")
    void partialTakePassesRest() {
        // given: B가 14:00 칸, C가 14:30 칸을 기다리고, 팀 A(14:00–15:00)가 취소해 B에게 넘어옴
        waitFor(4, 1, "14:00");
        now("10:01");
        waitFor(7, 1, "14:30");
        now("10:10");
        cancel(1, teamA);

        // when: B가 14:00–14:30만 받는다
        now("10:12");
        id(book(4, "14:00", "14:30", offerId(4)));

        // then: 남은 14:30–15:00이 C에게 10:17까지 넘어오고, 다른 사람은 그 30분을 못 잡는다
        assertThat(offers(7)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$[0].startAt", v -> v.assertThat().isEqualTo("2030-03-05T14:30:00+09:00"))
                .hasPathSatisfying("$[0].endAt", v -> v.assertThat().isEqualTo("2030-03-05T15:00:00+09:00"))
                .hasPathSatisfying("$[0].expiresAt", v -> v.assertThat().isEqualTo("2030-03-05T10:17:00+09:00"));
        assertThat(book(10, "14:30", "15:00", null)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RELAY_HELD");
        id(book(7, "14:30", "15:00", offerId(7)));
    }

    @Test
    @DisplayName("받기가 방 잠금을 쥔 동안 넘기기 정리가 그 잠금을 기다려도, 받기가 다른 방 넘김 행을 바꾸고 둘 다 교착 없이 끝난다")
    void takeAndAdvanceConcurrently() throws Exception {
        // given: B는 1번 방·2번 방 14:00 칸을 기다리고, C는 1번 방 15:00 칸을 기다린다.
        // 세 예약이 취소돼 B에게 1·2번 방, C에게 1번 방 15:00이 넘어오고, B의 2번 방·C의 1번 방 넘김은 방금 5분 지남
        // → advance가 손댈 방은 1번·2번 둘 다
        long teamRoom2 = id(mvc.post().uri("/api/reservations").header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":2,"startAt":"2030-03-05T14:00:00+09:00","endAt":"2030-03-05T15:00:00+09:00",
                         "participantStudentIds":["20230008","20230009"]}""")
                .exchange());
        long teamRoom1Later = id(book(10, "15:00", "16:00", null));
        waitFor(4, 1, "14:00");
        waitFor(4, 2, "14:00");
        now("10:10");
        cancel(1, teamA);
        cancel(7, teamRoom2);
        waitFor(7, 1, "15:00");
        cancel(10, teamRoom1Later);
        jdbc.sql("UPDATE relay_offer SET expires_at = :t WHERE (user_id = 4 AND room_id = 2) OR user_id = 7")
                .param("t", java.sql.Timestamp.from(at("10:09"))).update();

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection take = dataSource.getConnection()) {
            take.setAutoCommit(false);
            try (var st = take.createStatement()) {
                st.execute("SET LOCAL lock_timeout = '5s'");  // 교착·막힘이면 멈추지 않고 실패
                long takePid;
                try (var rs = st.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    takePid = rs.getLong(1);
                }

                // when 1: 받기가 하는 순서대로 먼저 1번 방 잠금을 쥔다
                st.execute("SELECT pg_advisory_xact_lock(-1)");

                // when 2: advance를 시작하고, 받기 세션에 막혀 기다리는 걸 pg_blocking_pids로 확인
                var advance = pool.submit(() -> relays.advance(clock.instant()));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (blockedBy(takePid) == 0) {
                    assertThat(advance.isDone()).as("advance가 받기의 1번 방 잠금을 기다려야 함").isFalse();
                    assertThat(System.nanoTime()).as("advance가 10초 안에 잠금 대기에 들어가야 함").isLessThan(deadline);
                    Thread.sleep(20);
                }

                // then 1: advance가 기다리는 중에 받기가 B의 2번 방 넘김 행을 바꿔도 막히지 않는다
                // (advance는 손댈 방 잠금을 다 잡기 전에는 넘김 행을 쥐지 않음)
                int changed = st.executeUpdate(
                        "UPDATE relay_offer SET status = 'DECLINED' WHERE user_id = 4 AND room_id = 2 AND status = 'OPEN'");
                assertThat(changed).isEqualTo(1);
                take.commit();

                // then 2: advance도 예외 없이 끝난다(교착이면 ExecutionException)
                assertThat(advance.get(30, java.util.concurrent.TimeUnit.SECONDS)).isNotNegative();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT status FROM relay_offer WHERE user_id = 4 AND room_id = 2").query(String.class).single())
                .isEqualTo("DECLINED");
        assertThat(jdbc.sql("SELECT status FROM relay_offer WHERE user_id = 7").query(String.class).single())
                .isEqualTo("EXPIRED");
    }

    /** 받기 세션(pid)에 막혀 잠금을 기다리는 세션 수 */
    int blockedBy(long pid) {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid = ANY(pg_blocking_pids(pid))")
                .param("pid", (int) pid).query(Integer.class).single();
    }

    @Test
    @DisplayName("넘길게요를 누르면 다음 사람에게 넘어가고, 5분이 지나면 1분 정리가 그다음 사람에게 넘긴다")
    void declineThenExpire() {
        // given: B → C → D 순서로 14:00 칸을 기다린다
        waitFor(4, 1, "14:00");
        now("10:01");
        waitFor(7, 1, "14:00");
        now("10:02");
        assertThat(waitFor(10, 1, "14:00")).bodyJson().extractingPath("$.order").isEqualTo(3);
        now("10:10");
        cancel(1, teamA);

        // when: B가 넘길게요
        assertThat(mvc.post().uri("/api/relay/offers/{id}/decline", offerId(4)).header("X-User-Id", "4").exchange())
                .hasStatus(204);

        // then: C에게 넘어온다
        long toC = offerId(7);
        assertThat(waitStatus(4)).isEqualTo("DECLINED");

        // when: C가 5분 동안 안 받음 → 1분 정리
        now("10:15");
        sweeps.sweep();

        // then: D에게 넘어오고, C의 넘김으로는 이제 못 잡는다
        offerId(10);
        assertThat(book(7, "14:00", "15:00", toC)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RELAY_OFFER_INVALID");
    }

    @Test
    @DisplayName("시작 30분 전부터 취소되면 넘기지 않고 그냥 빈 칸이 된다")
    void tooLateToRelay() {
        // given
        waitFor(4, 1, "14:00");

        // when: 13:31에 취소
        now("13:31");
        cancel(1, teamA);

        // then: 넘김 없이 누구나 잡을 수 있다
        assertThat(jdbc.sql("SELECT count(*) FROM relay_offer").query(Integer.class).single()).isZero();
        assertThat(book(10, "14:00", "15:00", null)).hasStatus(201);
    }

    @Test
    @DisplayName("기다리기는 차 있는 칸에만, 한 사람 3개까지, 시작 30분 전까지, 내 예약 칸은 안 된다")
    void waitRules() {
        // 빈 칸
        assertThat(waitFor(4, 1, "16:00")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RELAY_SLOT_FREE");
        // 내가(참여자로) 들어 있는 예약
        assertThat(waitFor(2, 1, "14:00")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RELAY_SLOT_FREE");
        // 칸 시작이 아님
        assertThat(waitFor(4, 1, "14:10")).hasStatus(400);

        // 3개까지 (같은 칸을 두 번 걸면 그대로 1개)
        id(book(7, "16:00", "18:00", null));
        assertThat(waitFor(4, 1, "14:00")).hasStatusOk();
        assertThat(waitFor(4, 1, "14:00")).hasStatusOk();
        assertThat(waitFor(4, 1, "16:00")).hasStatusOk();
        assertThat(waitFor(4, 1, "16:30")).hasStatusOk();
        assertThat(waitFor(4, 1, "17:00")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RELAY_WAIT_LIMIT");

        // 그만 기다리면 다시 걸 수 있다
        long first = jdbc.sql("SELECT min(id) FROM relay_wait WHERE user_id = 4").query(Long.class).single();
        assertThat(mvc.post().uri("/api/relay/waits/{id}/cancel", first).header("X-User-Id", "4").exchange())
                .hasStatus(204);
        assertThat(waitFor(4, 1, "17:00")).hasStatusOk();
        assertThat(mvc.get().uri("/api/relay/waits/me").header("X-User-Id", "4").exchange()).hasStatusOk()
                .bodyJson().extractingPath("$.length()").isEqualTo(3);

        // 시작 30분 전부터는 못 건다
        now("13:30");
        assertThat(waitFor(10, 1, "14:00")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RELAY_TOO_LATE");
    }

    @Test
    @DisplayName("남의 넘김이나 넘어온 구간 밖으로는 받을 수 없다")
    void invalidTake() {
        // given
        waitFor(4, 1, "14:00");
        now("10:10");
        cancel(1, teamA);
        long offer = offerId(4);

        // then: 남의 넘김
        assertThat(book(7, "14:00", "15:00", offer)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RELAY_OFFER_INVALID");
        // 구간 밖 (15:30까지)
        assertThat(book(4, "14:00", "15:30", offer)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RELAY_OFFER_INVALID");
        // 구간 안 일부만 받는 것은 된다
        assertThat(book(4, "14:00", "14:30", offer)).hasStatus(201);
    }
}
