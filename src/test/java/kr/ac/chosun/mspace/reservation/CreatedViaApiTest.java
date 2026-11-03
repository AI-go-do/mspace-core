package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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

/**
 * 어디서 만든 예약인지(created_via) 기록과 WEB/AGENT 건수 (NFR-COM-008, 11/3 영준 요청).
 * 지금 = 2026-11-03(화) 10:10 서울로 고정.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CreatedViaApiTest.FixedClock.class})
class CreatedViaApiTest {

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
        jdbc.sql("TRUNCATE reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 9; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
        // 9번 회원만 관리자. 건수는 9번으로 본다
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = 9").update();
    }

    /** owner 1·4·7이 각각 자기 팀(다음 두 사람)과 room번 방을 14:00–16:00에 */
    MvcTestResult book(int owner, int room, String client) {
        var req = mvc.post().uri("/api/reservations").header("X-User-Id", String.valueOf(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":%d,"startAt":"2026-11-03T14:00:00+09:00","endAt":"2026-11-03T16:00:00+09:00",
                         "participantStudentIds":["2023000%d","2023000%d"]}""".formatted(room, owner + 1, owner + 2));
        if (client != null) {
            req = req.header("X-Client", client);
        }
        return req.exchange();
    }

    String via(long id) {
        return jdbc.sql("SELECT created_via FROM reservation WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    MvcTestResult stats(String query) {
        return mvc.get().uri("/api/reservations/stats" + query).header("X-User-Id", "9").exchange();
    }

    @Test
    @DisplayName("X-Client 없이(웹 화면) 만들면 WEB, agent를 붙이면 AGENT로 저장된다")
    void recordsWhereItCameFrom() {
        assertThat(book(1, 1, null)).hasStatus(201);
        assertThat(book(4, 2, "agent")).hasStatus(201);
        assertThat(book(7, 3, " Agent ")).hasStatus(201);   // 대소문자·앞뒤 공백 무시

        assertThat(via(1)).isEqualTo("WEB");
        assertThat(via(2)).isEqualTo("AGENT");
        assertThat(via(3)).isEqualTo("AGENT");
    }

    @Test
    @DisplayName("지금 바로 쓰기(/instant)도 X-Client: agent면 AGENT")
    void instantFromAgent() {
        // 지금 10:10, 끝 11:00
        MvcTestResult r = mvc.post().uri("/api/reservations/instant").header("X-User-Id", "1")
                .header("X-Client", "agent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"endAt":"2026-11-03T11:00:00+09:00","participantStudentIds":["20230002","20230003"]}""")
                .exchange();

        assertThat(r).hasStatus(201);
        assertThat(via(1)).isEqualTo("AGENT");
    }

    @Test
    @DisplayName("X-Client가 web·agent가 아니면(오타) 400, 예약은 안 생긴다 (비서 숫자가 WEB으로 잘못 세지지 않게)")
    void unknownClientRejected() {
        assertThat(book(1, 1, "agnet")).hasStatus(400).bodyJson()
                .extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation").query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("건수: 기간 없이 부르면 전체, WEB·AGENT를 따로 센다 (노쇼·취소된 예약도 만든 건수에 들어간다)")
    void countsAll() {
        book(1, 1, null);
        book(4, 2, "agent");
        book(7, 3, "agent");
        jdbc.sql("UPDATE reservation SET status = 'NO_SHOW' WHERE id = 3").update();

        assertThat(stats("")).hasStatus(200).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.total").isEqualTo(3);
            json.assertThat().extractingPath("$.web").isEqualTo(1);
            json.assertThat().extractingPath("$.agent").isEqualTo(2);
            json.assertThat().extractingPath("$.from").isNull();
        });
    }

    @Test
    @DisplayName("건수: from·to는 서울 날짜로 양 끝 포함. 범위 밖에 만든 예약은 빠진다")
    void countsWithinDates() {
        book(1, 1, null);
        book(4, 2, "agent");
        book(7, 3, "agent");
        // 1번: 11/1 00:00 서울(범위 첫 순간), 2번: 11/2 23:59 서울(범위 마지막 날), 3번: 11/3 00:00 서울(범위 밖)
        jdbc.sql("UPDATE reservation SET created_at = '2026-11-01T00:00:00+09:00' WHERE id = 1").update();
        jdbc.sql("UPDATE reservation SET created_at = '2026-11-02T23:59:59+09:00' WHERE id = 2").update();
        jdbc.sql("UPDATE reservation SET created_at = '2026-11-03T00:00:00+09:00' WHERE id = 3").update();

        assertThat(stats("?from=2026-11-01&to=2026-11-02")).hasStatus(200).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.from").isEqualTo("2026-11-01");
            json.assertThat().extractingPath("$.to").isEqualTo("2026-11-02");
            json.assertThat().extractingPath("$.total").isEqualTo(2);
            json.assertThat().extractingPath("$.web").isEqualTo(1);
            json.assertThat().extractingPath("$.agent").isEqualTo(1);
        });
    }

    @Test
    @DisplayName("건수: from이 to보다 늦거나 날짜 모양이 틀리면 400")
    void badRange() {
        assertThat(stats("?from=2026-11-05&to=2026-11-01")).hasStatus(400);
        assertThat(stats("?from=11-01")).hasStatus(400);
    }

    @Test
    @DisplayName("건수: 관리자가 아니면(예약한 학생 본인이어도) 403 ADMIN_ONLY")
    void notAdmin() {
        book(1, 1, null);
        assertThat(mvc.get().uri("/api/reservations/stats").header("X-User-Id", "1").exchange())
                .hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("ADMIN_ONLY");
    }

    @Test
    @DisplayName("건수: 로그인 안 하면 401")
    void needsLogin() {
        assertThat(mvc.get().uri("/api/reservations/stats").exchange()).hasStatus(401);
    }
}
