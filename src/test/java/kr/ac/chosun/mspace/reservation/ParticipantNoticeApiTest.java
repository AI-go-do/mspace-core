package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
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

/** 참여자 알림 (11/9 지영): 대표자가 예약하면 참여자에게 알림. 지금은 2026-11-03(화) 10:10, 회원 1–6(학번 2023000N) */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ParticipantNoticeApiTest.FixedClock.class})
class ParticipantNoticeApiTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-03T01:10:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE notification, reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 6; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "p" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
    }

    /** 대표자 owner가 1번 방을 잡는다. key가 있으면 Idempotency-Key로 보낸다 */
    MvcTestResult reserve(long owner, String start, String end, String key, String... others) {
        String ids = others.length == 0 ? "" : "\"" + String.join("\",\"", others) + "\"";
        var req = mvc.post().uri("/api/reservations").header("X-User-Id", String.valueOf(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2026-11-03T%s:00+09:00","endAt":"2026-11-03T%s:00+09:00","participantStudentIds":[%s]}"""
                        .formatted(start, end, ids));
        return key == null ? req.exchange() : req.header("Idempotency-Key", key).exchange();
    }

    List<Long> notified() {
        return jdbc.sql("SELECT user_id FROM notification WHERE kind = 'PARTICIPANT_ADDED' ORDER BY user_id")
                .query(Long.class).list();
    }

    @Test
    @DisplayName("미리 예약을 만들면 대표자를 뺀 참여자 두 명이 알림을 받고, 문구에 방·시각만 있고 이름·번호는 없다")
    void participantsNotified() {
        // when
        MvcTestResult r = reserve(1, "14:00", "16:00", null, "20230002", "20230003");

        // then
        assertThat(r).hasStatus(201);
        assertThat(notified()).containsExactly(2L, 3L);
        var row = jdbc.sql("SELECT reservation_id, title, body FROM notification LIMIT 1").query().singleRow();
        assertThat(row).containsEntry("reservation_id", 1L).containsEntry("title", "함께 쓸 예약이 잡혔어요");
        assertThat((String) row.get("body"))
                .isEqualTo("1번 방 11/3 14:00–16:00 예약에 함께 쓰는 사람으로 들어갔어요. 입실 번호는 대표자에게 있어요.")
                .doesNotContain("홍길동").doesNotContain("2023000");
    }

    @Test
    @DisplayName("즉시 시작도 참여자에게 알림이 간다")
    void instantStartNotifies() {
        // when: 10:10에 눌러서 11:00까지
        MvcTestResult r = mvc.post().uri("/api/reservations/instant").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"endAt":"2026-11-03T11:00:00+09:00","participantStudentIds":["20230002","20230003"]}""")
                .exchange();

        // then
        assertThat(r).hasStatus(201);
        assertThat(notified()).containsExactly(2L, 3L);
        assertThat(jdbc.sql("SELECT body FROM notification LIMIT 1").query(String.class).single())
                .startsWith("1번 방 11/3 10:10–11:00");
    }

    @Test
    @DisplayName("같은 멱등 키로 다시 보내면(200 재생) 알림이 또 가지 않는다")
    void replayDoesNotRepeat() {
        assertThat(reserve(1, "14:00", "16:00", "k-1", "20230002", "20230003")).hasStatus(201);

        // when
        MvcTestResult again = reserve(1, "14:00", "16:00", "k-1", "20230002", "20230003");

        // then
        assertThat(again).hasStatus(200);
        assertThat(notified()).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("예약이 실패하면(겹침 409) 알림도 없다")
    void failedReservationNoNotice() {
        assertThat(reserve(1, "14:00", "16:00", null, "20230002", "20230003")).hasStatus(201);

        // when: 다른 팀이 같은 시간
        MvcTestResult r = reserve(4, "15:00", "16:00", null, "20230005", "20230006");

        // then
        assertThat(r).hasStatus(409);
        assertThat(notified()).containsExactly(2L, 3L);
    }
}
