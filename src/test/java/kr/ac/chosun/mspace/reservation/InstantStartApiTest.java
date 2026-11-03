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
 * 즉시 시작(FR-RSV-103)과 연장(FR-RSV-104). 날짜는 2030-03-05(화), 1번 방(3–5명).
 * 대표자 1번 + 2·3번(우리 팀), 대표자 4번 + 5·6번(다른 팀).
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, InstantStartApiTest.MovableClock.class})
class InstantStartApiTest {

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
    }

    /** 우리 팀(대표자 1번)이 1번 방을 지금부터 end까지 */
    MvcTestResult startNow(String end) {
        return mvc.post().uri("/api/reservations/instant").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"endAt":"2030-03-05T%s:00+09:00","participantStudentIds":["20230002","20230003"]}"""
                        .formatted(end))
                .exchange();
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
                        {"eventId":"i-%d","roomId":1,"deviceId":"keypad-1","code":"%s"}""".formatted(++eventSeq, code))
                .exchange();
    }

    String status(long id) {
        return jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", id).query(String.class).single();
    }

    Instant column(long id, String column) {
        return jdbc.sql("SELECT " + column + " FROM reservation WHERE id = :id").param("id", id)
                .query(OffsetDateTime.class).single().toInstant();
    }

    @Test
    @DisplayName("수용 기준: 14:17:42에 누르면 시작 14:17:42, 끝 16:30까지 고를 수 있고 입실 마감은 14:27:42")
    void startsAtPressTime() {
        now("14:17:42");
        assertThat(startNow("17:00")).hasStatus(400);   // 최대 16:30
        assertThat(startNow("14:45")).hasStatus(400);   // 30분 칸 아님
        assertThat(startNow("14:00")).hasStatus(400);   // 지난 시각

        MvcTestResult r = startNow("16:30");
        assertThat(r).hasStatus(201);
        assertThat(r).bodyJson().extractingPath("$.startAt").isEqualTo("2030-03-05T14:17:42+09:00");
        assertThat(r).bodyJson().extractingPath("$.endAt").isEqualTo("2030-03-05T16:30:00+09:00");
        assertThat(jdbc.sql("SELECT is_instant FROM reservation WHERE id = 1").query(Boolean.class).single()).isTrue();
        assertThat(column(1, "checkin_deadline")).isEqualTo(at("14:27:42"));
    }

    @Test
    @DisplayName("수용 기준: 다음 예약(15:00–16:00) 전까지만. 16:00까지 → 409, 15:00까지 → 201")
    void untilNextReservation() {
        now("10:00");
        assertThat(book(4, "15:00", "16:00")).hasStatus(201);

        now("14:17");
        assertThat(startNow("16:00")).hasStatus(409);
        assertThat(startNow("15:00")).hasStatus(201);
    }

    @Test
    @DisplayName("지금 다른 예약 시간(14:00–15:00)이면 409. 그 예약이 노쇼(14:15 마감)가 되면 정리를 기다리지 않고 바로 201")
    void roomInUseThenFreedByNoShow() {
        now("10:00");
        assertThat(book(4, "14:00", "15:00")).hasStatus(201);

        now("14:10");
        assertThat(startNow("15:00")).hasStatus(409);

        now("14:15:30");   // 1분 정리는 아직 안 돌았지만, 만들기 직전에 정리한다
        assertThat(startNow("15:00")).hasStatus(201);
        assertThat(status(1)).isEqualTo("NO_SHOW");
    }

    @Test
    @DisplayName("수용 기준: 10분 안에 키패드 → 입실, 10분이 지나면 키패드 거절·노쇼")
    void tenMinutesToEnter() {
        now("14:17");
        assertThat(startNow("15:00")).hasStatus(201);
        now("14:27");
        assertThat(press(1)).bodyJson().extractingPath("$.result").isEqualTo("OPENED");
        assertThat(status(1)).isEqualTo("IN_USE");

        jdbc.sql("UPDATE reservation SET status = 'CHECKED_OUT', checked_out_at = now() WHERE id = 1").update();
        now("14:30");
        assertThat(startNow("15:30")).hasStatus(201);
        now("14:40:01");
        assertThat(press(2)).bodyJson().extractingPath("$.reason").isEqualTo("WRONG_CODE");
        sweeps.sweep();
        assertThat(status(2)).isEqualTo("NO_SHOW");
    }

    @Test
    @DisplayName("수용 기준(연장): 14:00–15:00을 쓰고 15:00:20에 바로 즉시 시작 → 정리 전이라도 201, 앞 예약은 15:00 자동 퇴실")
    void extendRightAfterEnd() {
        now("10:00");
        assertThat(book(1, "14:00", "15:00")).hasStatus(201);
        now("14:05");
        press(1);

        now("15:00:20");
        assertThat(startNow("17:00")).hasStatus(201);
        assertThat(status(1)).isEqualTo("AUTO_CHECKED_OUT");
        assertThat(column(1, "checked_out_at")).isEqualTo(at("15:00"));
    }

    @Test
    @DisplayName("수용 기준: 일찍 퇴실하면 남은 시간에 바로 다시 즉시 시작할 수 있다 (2시간 넘게 이어 쓰기 허용)")
    void restartAfterEarlyCheckout() {
        now("10:00");
        assertThat(book(1, "14:00", "16:00")).hasStatus(201);
        now("14:05");
        press(1);

        now("15:40");
        assertThat(mvc.post().uri("/api/reservations/1/checkout").header("X-User-Id", "1").exchange()).hasStatusOk();
        assertThat(startNow("17:30")).hasStatus(201);
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 다시 보내면 (시작 시각이 달라도) 처음 즉시 시작을 200으로")
    void idempotentReplay() {
        now("14:17");
        var first = mvc.post().uri("/api/reservations/instant").header("X-User-Id", "1").header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"endAt":"2030-03-05T15:00:00+09:00","participantStudentIds":["20230002","20230003"]}""")
                .exchange();
        assertThat(first).hasStatus(201);

        now("14:17:30");
        var again = mvc.post().uri("/api/reservations/instant").header("X-User-Id", "1").header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"endAt":"2030-03-05T15:00:00+09:00","participantStudentIds":["20230002","20230003"]}""")
                .exchange();
        assertThat(again).hasStatus(200);
        assertThat(again).bodyJson().extractingPath("$.id").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM reservation").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("미리 예약에 쓴 Idempotency-Key를 지금 바로 쓰기에 다시 쓰면 (방·끝·참여자가 같아도) 409")
    void keyFromBookingIsNotReplayedAsInstant() {
        now("13:55");
        var booked = mvc.post().uri("/api/reservations").header("X-User-Id", "1").header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2030-03-05T14:00:00+09:00","endAt":"2030-03-05T15:00:00+09:00",
                         "participantStudentIds":["20230002","20230003"]}""")
                .exchange();
        assertThat(booked).hasStatus(201);

        now("13:55:10");
        var instant = mvc.post().uri("/api/reservations/instant").header("X-User-Id", "1").header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"endAt":"2030-03-05T15:00:00+09:00","participantStudentIds":["20230002","20230003"]}""")
                .exchange();
        assertThat(instant).hasStatus(409);
        assertThat(instant).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("입실 마감보다 끝이 먼저인 지금 바로 쓰기(14:29–14:30)를 안 쓰면 끝 시각에 노쇼, 대표자는 바로 다시 잡을 수 있다")
    void shortInstantEndsBeforeDeadline() {
        now("14:29");
        assertThat(startNow("14:30")).hasStatus(201);
        assertThat(column(1, "checkin_deadline")).isEqualTo(at("14:39"));

        now("14:30");
        assertThat(sweeps.sweep()).isEqualTo(new SweepReservationsUseCase.Swept(1, 0));
        assertThat(status(1)).isEqualTo("NO_SHOW");
        assertThat(startNow("15:00")).hasStatus(201);
    }

    @Test
    @DisplayName("끝 시각 빠짐 → 400, 로그인 없음 → 401")
    void badRequests() {
        now("14:17");
        assertThat(mvc.post().uri("/api/reservations/instant").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"participantStudentIds":["20230002","20230003"]}""").exchange()).hasStatus(400);
        assertThat(mvc.post().uri("/api/reservations/instant").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"endAt":"2030-03-05T15:00:00+09:00","participantStudentIds":["20230002","20230003"]}""")
                .exchange()).hasStatus(401);
    }
}
