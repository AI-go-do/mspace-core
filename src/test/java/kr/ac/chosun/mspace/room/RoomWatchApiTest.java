package kr.ac.chosun.mspace.room;

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
 * 빈방 알림. 날짜는 2030-03-05(화), 1번 방. 팀 A(1 + 2·3)가 14:00–15:00을 잡아 두고, 4번이 그 방을 지켜본다.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RoomWatchApiTest.MovableClock.class})
class RoomWatchApiTest {

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

    long teamA;

    void now(String day, String time) {
        clock.now = OffsetDateTime.parse("2030-03-%sT%s:00+09:00".formatted(day, time)).toInstant();
    }

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE room_watch, relay_offer, relay_wait, notification, reservation, reservation_participant, "
                + "users RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 4; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "%08d".formatted(20230000 + i)).update();
        }
        now("05", "10:00");
        MvcTestResult r = mvc.post().uri("/api/reservations").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2030-03-05T14:00:00+09:00","endAt":"2030-03-05T15:00:00+09:00",
                         "participantStudentIds":["20230002","20230003"]}""")
                .exchange();
        assertThat(r).hasStatus(201);
        teamA = Long.parseLong(r.getResponse().getHeader("Location").replaceAll(".*/", ""));
    }

    MvcTestResult watch(int user, int room) {
        return mvc.post().uri("/api/room-watches").header("X-User-Id", String.valueOf(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":%d}".formatted(room)).exchange();
    }

    MvcTestResult mine(int user) {
        return mvc.get().uri("/api/room-watches/me").header("X-User-Id", String.valueOf(user)).exchange();
    }

    int freeNotices(int user) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE user_id = :u AND kind = 'ROOM_FREE'").param("u", user)
                .query(Integer.class).single();
    }

    @Test
    @DisplayName("쓰는 중인 방을 지켜보다 예약이 끝나 비면 1분 정리가 한 번 알리고 목록에서 빠진다")
    void notifiesOnceWhenFreed() {
        // given: 14:05, 팀 A 예약 시간이라 방이 차 있다
        now("05", "14:05");
        assertThat(watch(4, 1)).hasStatusOk().bodyJson().extractingPath("$.roomId").isEqualTo(1);
        // 두 번 켜도 하나
        assertThat(watch(4, 1)).hasStatusOk();
        assertThat(mine(4)).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(1);

        // when: 아직 차 있을 때 정리
        sweeps.sweep();
        // then: 알림 없음
        assertThat(freeNotices(4)).isZero();

        // when: 팀 A가 입실하지 않아 노쇼 처리되는 정리 (시작 + 15분 뒤)
        now("05", "14:16");
        sweeps.sweep();

        // then: 알림 한 번, 목록에서 빠짐
        assertThat(freeNotices(4)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT title FROM notification WHERE user_id = 4").query(String.class).single())
                .contains("비었어요");
        assertThat(mine(4)).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(0);

        // when: 다시 정리해도 두 번 알리지 않는다
        now("05", "14:17");
        sweeps.sweep();
        assertThat(freeNotices(4)).isEqualTo(1);
    }

    @Test
    @DisplayName("지금 빈 방은 지켜볼 수 없고(409), 없는 방은 404, 끄면 알림이 오지 않는다")
    void rules() {
        // 10:00에는 1번 방이 비어 있다
        assertThat(watch(4, 1)).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("ROOM_WATCH_FREE");
        assertThat(watch(4, 99)).hasStatus(404);
        assertThat(mvc.post().uri("/api/room-watches").header("X-User-Id", "4")
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(400);

        // 켰다가 끄면
        now("05", "14:05");
        assertThat(watch(4, 1)).hasStatusOk();
        assertThat(mvc.post().uri("/api/room-watches/{room}/cancel", 1).header("X-User-Id", "4").exchange())
                .hasStatus(204);
        assertThat(mine(4)).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(0);
        now("05", "14:16");
        sweeps.sweep();
        assertThat(freeNotices(4)).isZero();
    }

    @Test
    @DisplayName("지켜보기는 그날 하루만이라, 다음 날에는 알리지 않는다")
    void onlyThatDay() {
        // given: 14:05에 켬
        now("05", "14:05");
        assertThat(watch(4, 1)).hasStatusOk();

        // when: 다음 날 정리 (방은 비어 있다)
        now("06", "09:00");
        sweeps.sweep();

        // then
        assertThat(freeNotices(4)).isZero();
        assertThat(mine(4)).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(0);
    }
}
