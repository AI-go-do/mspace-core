package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Command;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Created;
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
 * 예약 만들기 멱등 키 (#10, NFR-COM-008·영준 비서 M2). 지금 = 2026-11-03(화) 10:10 서울로 고정.
 * 비서가 응답을 못 받아 같은 확인을 다시 보내도 예약이 두 번 생기지 않는지 본다.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IdempotencyKeyApiTest.FixedClock.class})
class IdempotencyKeyApiTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-03T01:10:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    static final String BODY = """
            {"roomId":1,"startAt":"2026-11-03T14:00:00+09:00","endAt":"2026-11-03T16:00:00+09:00",
             "participantStudentIds":["20230002","20230003"]}""";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CreateReservationUseCase service;

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 9; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
    }

    MvcTestResult reserve(long ownerId, String key, String body) {
        var req = mvc.post().uri("/api/reservations")
                .header("X-User-Id", String.valueOf(ownerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return req.exchange();
    }

    int reservations() {
        return jdbc.sql("SELECT count(*) FROM reservation").query(Integer.class).single();
    }

    @Test
    @DisplayName("수용 기준: 같은 Idempotency-Key로 두 번 → 예약 1건, 처음 201·다음 200, 같은 id·같은 키패드 번호")
    void sameKeyTwice() {
        MvcTestResult first = reserve(1, "confirm-abc", BODY);
        MvcTestResult second = reserve(1, "confirm-abc", BODY);

        assertThat(first).hasStatus(201);
        assertThat(second).hasStatus(200);
        String code = jdbc.sql("SELECT access_code FROM reservation WHERE id = 1").query(String.class).single();
        assertThat(second).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.id").isEqualTo(1);
            json.assertThat().extractingPath("$.status").isEqualTo("BOOKED");
            json.assertThat().extractingPath("$.accessCode").isEqualTo(code);
        });
        assertThat(reservations()).isEqualTo(1);
    }

    @Test
    @DisplayName("참여자 순서만 다르면 같은 요청으로 본다 → 200")
    void participantOrderDoesNotMatter() {
        assertThat(reserve(1, "k1", BODY)).hasStatus(201);
        assertThat(reserve(1, "k1", BODY.replace("\"20230002\",\"20230003\"", "\"20230003\",\"20230002\"")))
                .hasStatus(200);
    }

    @Test
    @DisplayName("같은 키를 다른 내용(시각)에 쓰면 → 409 IDEMPOTENCY_KEY_REUSED, 새 예약 없음")
    void sameKeyDifferentRequest() {
        assertThat(reserve(1, "k1", BODY)).hasStatus(201);

        assertThat(reserve(1, "k1", BODY.replace("16:00", "15:00")))
                .hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(reservations()).isEqualTo(1);
    }

    @Test
    @DisplayName("키는 대표자마다 따로: 다른 대표자가 같은 키를 써도 각자 새 예약 → 201")
    void keyIsPerOwner() {
        assertThat(reserve(1, "k1", BODY)).hasStatus(201);
        assertThat(reserve(4, "k1", """
                {"roomId":2,"startAt":"2026-11-03T14:00:00+09:00","endAt":"2026-11-03T16:00:00+09:00",
                 "participantStudentIds":["20230005","20230006"]}""")).hasStatus(201);
        assertThat(reservations()).isEqualTo(2);
    }

    @Test
    @DisplayName("키 없이 같은 요청 두 번은 지금처럼 두 번째가 409 (웹 화면)")
    void noKey() {
        assertThat(reserve(1, null, BODY)).hasStatus(201);
        assertThat(reserve(1, null, BODY)).hasStatus(409);
    }

    @Test
    @DisplayName("빈 키, 65자 키 → 400")
    void invalidKey() {
        assertThat(reserve(1, " ", BODY)).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(reserve(1, "a".repeat(65), BODY)).hasStatus(400);
        assertThat(reserve(1, "a".repeat(64), BODY)).hasStatus(201);
    }

    @Test
    @DisplayName("같은 키로 동시에 20번 → 예약 1건, 1번은 새로 만들고 19번은 같은 예약을 돌려준다(500·409 없음)")
    void concurrentSameKey() throws Exception {
        int n = 20;
        Command command = new Command(1, Instant.parse("2026-11-03T05:00:00Z"), Instant.parse("2026-11-03T07:00:00Z"),
                List.of("20230002", "20230003"), "same-key");
        ExecutorService pool = Executors.newFixedThreadPool(n);
        Map<String, Integer> results = new ConcurrentHashMap<>();
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try {
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try {
                        go.await();
                        Created c = service.create(1, command);
                        ids.add(c.id());
                        results.merge(c.replayed() ? "replayed" : "created", 1, Integer::sum);
                    } catch (Exception e) {
                        results.merge(e.getClass().getSimpleName() + ":" + e.getMessage(), 1, Integer::sum);
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
        assertThat(results).isEqualTo(Map.of("created", 1, "replayed", n - 1));
        assertThat(ids).hasSize(1);
        assertThat(reservations()).isEqualTo(1);
    }
}
