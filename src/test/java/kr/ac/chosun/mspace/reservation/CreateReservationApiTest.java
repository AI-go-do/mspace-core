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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 예약 생성 API (FR-RSV-001~005). 지금 = 2026-11-03(화) 10:10 서울로 고정.
 * 진짜로 커밋해야 커밋 때 도는 인원 확인 트리거까지 검증되므로 @Transactional 롤백을 쓰지 않고,
 * 테스트마다 시작 전에 예약·회원 테이블을 비운다.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CreateReservationApiTest.FixedClock.class})
class CreateReservationApiTest {

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
        // 1~9번: 학생 인증 마친 회원(학번 2023000N), 10번: 인증 안 한 회원
        for (int i = 1; i <= 9; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
        jdbc.sql("INSERT INTO users (email, student_id, name) VALUES ('u10@chosun.ac.kr', '20230010', '미인증')")
                .update();
    }

    MvcTestResult reserve(long ownerId, int roomId, String start, String end, String... others) {
        String ids = String.join("\",\"", others);
        String body = """
                {"roomId":%d,"startAt":"%s","endAt":"%s","participantStudentIds":[%s]}
                """.formatted(roomId, start, end, others.length == 0 ? "" : "\"" + ids + "\"");
        return mvc.post().uri("/api/reservations")
                .header("X-User-Id", String.valueOf(ownerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    static String at(String dayTime) {
        return "2026-11-" + dayTime + ":00+09:00";
    }

    int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    @Test
    @DisplayName("수용 기준: 1번 방 오늘 14:00–16:00, 3명 → 201, BOOKED, 6자리 번호, 참여자 3명 저장")
    void created() {
        assertThat(reserve(1, 1, at("03T14:00"), at("03T16:00"), "20230002", "20230003"))
                .hasStatus(201)
                .hasHeader("Location", "/api/reservations/1")
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.status").isEqualTo("BOOKED");
                    json.assertThat().extractingPath("$.headcount").isEqualTo(3);
                    json.assertThat().extractingPath("$.startAt").isEqualTo("2026-11-03T14:00:00+09:00");
                    json.assertThat().extractingPath("$.accessCode").asString().matches("[0-9]{6}");
                });
        assertThat(count("SELECT count(*) FROM reservation_participant WHERE reservation_id = 1")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM reservation_participant WHERE is_owner AND user_id = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("수용 기준: 14:10 시작 → 400 (30분 단위 아님)")
    void notOnSlot() {
        assertThat(reserve(1, 1, at("03T14:10"), at("03T16:00"), "20230002", "20230003"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_ON_SLOT");
    }

    @Test
    @DisplayName("수용 기준: 2시간 30분 → 400, 끝이 시작보다 앞 → 400")
    void tooLong() {
        assertThat(reserve(1, 1, at("03T14:00"), at("03T16:30"), "20230002", "20230003"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_INVALID_LENGTH");
        assertThat(reserve(1, 1, at("03T16:00"), at("03T14:00"), "20230002", "20230003"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_INVALID_LENGTH");
    }

    @Test
    @DisplayName("수용 기준: 7일째(11/9) 23:00–01:00 → 201 (자정 넘김), 8일째(11/10) 00:00 → 400")
    void bookingWindow() {
        assertThat(reserve(1, 1, at("09T23:00"), at("10T01:00"), "20230002", "20230003")).hasStatus(201);
        assertThat(reserve(4, 2, at("10T00:00"), at("10T01:00"), "20230005", "20230006"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_OUT_OF_RANGE");
    }

    @Test
    @DisplayName("수용 기준: 지난 시각(오늘 10:00, 지금 10:10) → 400")
    void past() {
        assertThat(reserve(1, 1, at("03T10:00"), at("03T11:00"), "20230002", "20230003"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_IN_PAST");
    }

    @Test
    @DisplayName("수용 기준: 14:00–16:00이 있으면 15:00–17:00 → 409, 16:00–17:00 → 201, 2번 방 같은 시간 → 201")
    void roomOverlap() {
        assertThat(reserve(1, 1, at("03T14:00"), at("03T16:00"), "20230002", "20230003")).hasStatus(201);

        assertThat(reserve(4, 1, at("03T15:00"), at("03T17:00"), "20230005", "20230006"))
                .hasStatus(409).bodyJson().extractingPath("$.message").isEqualTo("이미 예약된 시간입니다");
        assertThat(reserve(4, 1, at("03T16:00"), at("03T17:00"), "20230005", "20230006")).hasStatus(201);
        assertThat(reserve(7, 2, at("03T14:00"), at("03T16:00"), "20230008", "20230009")).hasStatus(201);
    }

    @Test
    @DisplayName("수용 기준: 취소된 예약 시간은 다시 잡을 수 있다")
    void canceledFreesRoom() {
        assertThat(reserve(1, 1, at("03T14:00"), at("03T16:00"), "20230002", "20230003")).hasStatus(201);
        jdbc.sql("UPDATE reservation SET status = 'CANCELED', canceled_at = now() WHERE id = 1").update();

        assertThat(reserve(4, 1, at("03T14:00"), at("03T16:00"), "20230005", "20230006")).hasStatus(201);
    }

    @Test
    @DisplayName("수용 기준: 끝나지 않은 내 예약이 있으면 다른 날 예약도 409 (대표자 1건)")
    void oneActivePerOwner() {
        assertThat(reserve(1, 1, at("04T14:00"), at("04T16:00"), "20230002", "20230003")).hasStatus(201);

        assertThat(reserve(1, 2, at("05T14:00"), at("05T16:00"), "20230004", "20230005"))
                .hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("OWNER_HAS_ACTIVE_RESERVATION");
    }

    @Test
    @DisplayName("수용 기준: 1번 방(3–5명)에 2명 → 400, 5번 방(5–8명)에 9명 → 400")
    void headcount() {
        assertThat(reserve(1, 1, at("03T14:00"), at("03T16:00"), "20230002"))
                .hasStatus(400).bodyJson().extractingPath("$.message")
                .isEqualTo("1번 방은 3명 이상 5명 이하입니다 (입력: 2명)");
        assertThat(reserve(1, 5, at("03T14:00"), at("03T16:00"),
                "20230002", "20230003", "20230004", "20230005", "20230006", "20230007", "20230008", "20230009"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("HEADCOUNT_OUT_OF_RANGE");
        assertThat(count("SELECT count(*) FROM reservation")).isZero();
    }

    @Test
    @DisplayName("수용 기준: A가 1번 방 14–16시 참여 중이면 A를 넣은 2번 방 15–16시 → 409, 16–17시 → 201")
    void personOverlap() {
        assertThat(reserve(1, 1, at("03T14:00"), at("03T16:00"), "20230002", "20230003")).hasStatus(201);

        assertThat(reserve(4, 2, at("03T15:00"), at("03T16:00"), "20230002", "20230005"))
                .hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("PARTICIPANT_TIME_TAKEN");
        assertThat(reserve(4, 2, at("03T16:00"), at("03T17:00"), "20230002", "20230005")).hasStatus(201);
    }

    @Test
    @DisplayName("수용 기준: 같은 학번 두 번(대표자 본인 포함) → 400")
    void duplicateStudent() {
        assertThat(reserve(1, 1, at("03T14:00"), at("03T16:00"), "20230002", "20230002", "20230003"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("PARTICIPANT_DUPLICATE");
        assertThat(reserve(1, 1, at("03T14:00"), at("03T16:00"), "20230001", "20230003"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("PARTICIPANT_DUPLICATE");
    }

    @Test
    @DisplayName("가입 안 한 학번, 학생 인증 안 한 회원 → 400")
    void unknownStudent() {
        assertThat(reserve(1, 1, at("03T14:00"), at("03T16:00"), "20230002", "29999999"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("PARTICIPANT_NOT_FOUND");
        assertThat(reserve(1, 1, at("03T14:00"), at("03T16:00"), "20230002", "20230010"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("PARTICIPANT_NOT_FOUND");
    }

    @Test
    @DisplayName("사용 불가 방 → 404, 로그인 없음 → 401, 본문 형식 오류 → 400")
    void otherErrors() {
        jdbc.sql("UPDATE rooms SET is_active = false WHERE id = 3").update();
        try {
            assertThat(reserve(1, 3, at("03T14:00"), at("03T16:00"), "20230002", "20230003")).hasStatus(404);
        } finally {
            jdbc.sql("UPDATE rooms SET is_active = true WHERE id = 3").update();
        }

        assertThat(mvc.post().uri("/api/reservations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .hasStatus(401);
        assertThat(mvc.post().uri("/api/reservations").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":1,\"startAt\":\"내일\"}"))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
    }
}
