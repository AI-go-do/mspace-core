package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * 동시 요청 100건 (NFR-COM-002, FR-RSV-002·003 수용 기준).
 * 진짜 서버(RANDOM_PORT)에 HTTP로 100건을 "같은 순간"에 보낸다.
 * CountDownLatch로 100개 스레드를 모두 출발선에 세운 뒤 한꺼번에 출발시킨다.
 */
// 연결 풀을 요청 수(100)보다 크게: 기본값 10이면 DB에는 10개씩만 동시에 들어간다
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "mspace.auth.dev-header=true", "spring.datasource.hikari.maximum-pool-size=110"})
@Import({TestcontainersConfiguration.class, ConcurrentReservationTest.FixedClock.class})
class ConcurrentReservationTest {

    static final int N = 100;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-03T01:10:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        jdbc.sql("TRUNCATE reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        // 회원 300명 (학번 2024000001 ~ 2024000300), 모두 학생 인증 마침
        jdbc.sql("""
                INSERT INTO users (email, student_id, name, verified_at)
                SELECT 'u' || i || '@chosun.ac.kr', (2024000000 + i)::text, '회원', now()
                  FROM generate_series(1, 300) i
                """).update();
    }

    static String sid(int userId) {
        return String.valueOf(2024000000 + userId);
    }

    /** 100건을 동시에 보내고 응답 코드별 개수를 센다 */
    Map<Integer, Integer> fire(List<Callable<Integer>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (Callable<Integer> r : requests) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return r.call();
            }));
        }
        ready.await();
        go.countDown();   // 출발
        Map<Integer, Integer> counts = new ConcurrentHashMap<>();
        for (Future<Integer> f : results) {
            counts.merge(f.get(), 1, Integer::sum);
        }
        pool.shutdown();
        return counts;
    }

    int post(long ownerId, int roomId, String start, String end, String p1, String p2) {
        String body = """
                {"roomId":%d,"startAt":"%s","endAt":"%s","participantStudentIds":["%s","%s"]}
                """.formatted(roomId, start, end, p1, p2);
        return client.post().uri("/api/reservations")
                .header("X-User-Id", String.valueOf(ownerId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    @Test
    @DisplayName("서로 다른 100팀이 같은 방·같은 시간을 동시에 예약하면 1건만 201, 99건 409, DB에는 1건")
    void sameRoomSameTime() throws Exception {
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int team = 0; team < N; team++) {
            int owner = team * 3 + 1;   // 팀마다 다른 3명 (1,2,3 / 4,5,6 / ...)
            requests.add(() -> post(owner, 1, "2026-11-04T14:00:00+09:00", "2026-11-04T16:00:00+09:00",
                    sid(owner + 1), sid(owner + 2)));
        }

        long started = System.nanoTime();
        Map<Integer, Integer> counts = fire(requests);
        long ms = (System.nanoTime() - started) / 1_000_000;

        int saved = jdbc.sql("SELECT count(*) FROM reservation WHERE room_id = 1 AND status = 'BOOKED'")
                .query(Integer.class).single();
        System.out.printf("[동시 100건] 같은 방·시간: %s, DB 저장 %d건, %dms%n", counts, saved, ms);

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(201, 1, 409, N - 1));
        assertThat(saved).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 대표자가 서로 다른 방·시간으로 100건을 동시에 보내도 1건만 201 (대표자 1건)")
    void sameOwnerManyRequests() throws Exception {
        List<Callable<Integer>> requests = new ArrayList<>();
        AtomicInteger i = new AtomicInteger();
        for (int k = 0; k < N; k++) {
            int room = k % 4 + 1;                 // 1~4번 방
            int day = 4 + (k / 4) / 10;           // 11/4 ~ 11/6
            int hour = 8 + (k / 4) % 10;          // 08시 ~ 17시
            String start = "2026-11-0%dT%02d:00:00+09:00".formatted(day, hour);
            String end = "2026-11-0%dT%02d:00:00+09:00".formatted(day, hour + 1);
            requests.add(() -> {
                i.incrementAndGet();
                return post(1, room, start, end, sid(2), sid(3));
            });
        }

        Map<Integer, Integer> counts = fire(requests);

        int saved = jdbc.sql("SELECT count(*) FROM reservation WHERE owner_id = 1")
                .query(Integer.class).single();
        System.out.printf("[동시 100건] 같은 대표자: %s, DB 저장 %d건%n", counts, saved);

        assertThat(i.get()).isEqualTo(N);
        assertThat(counts.get(201)).isEqualTo(1);
        assertThat(saved).isEqualTo(1);
    }
}
