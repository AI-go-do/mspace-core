package kr.ac.chosun.mspace.friend;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 친구 (FR-RSV-004 변경, 11/6 지영 결정): 서로 수락해야 친구, 삭제 가능, 예약 참여자는 친구만 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.reservation.friends-only=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FriendApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, friendship, users RESTART IDENTITY CASCADE").update();
        // 1 김지영 2 홍길동 3 박수연 4 이영준 (인증), 5 최재웅 (인증 전)
        jdbc.sql("""
                INSERT INTO users (email, student_id, name, verified_at) VALUES
                  ('u1@chosun.ac.kr', '20230001', '김지영', now()),
                  ('u2@chosun.ac.kr', '20230002', '홍길동', now()),
                  ('u3@chosun.ac.kr', '20230003', '박수연', now()),
                  ('u4@chosun.ac.kr', '20230004', '이영준', now()),
                  ('u5@chosun.ac.kr', '20230005', '최재웅', NULL)
                """).update();
    }

    MvcTestResult request(long from, String studentId) {
        return mvc.post().uri("/api/friends/requests").header("X-User-Id", String.valueOf(from))
                .contentType(MediaType.APPLICATION_JSON).content("{\"studentId\":\"%s\"}".formatted(studentId)).exchange();
    }

    MvcTestResult act(long who, long requestId, String verb) {
        return mvc.post().uri("/api/friends/requests/{id}/{verb}", requestId, verb)
                .header("X-User-Id", String.valueOf(who)).exchange();
    }

    MvcTestResult list(long who) {
        return mvc.get().uri("/api/friends").header("X-User-Id", String.valueOf(who)).exchange();
    }

    MvcTestResult remove(long who, String studentId) {
        return mvc.delete().uri("/api/friends/{sid}", studentId).header("X-User-Id", String.valueOf(who)).exchange();
    }

    static long requestId(MvcTestResult r) {
        return ((Number) JsonPath.read(body(r), "$.requestId")).longValue();
    }

    static String body(MvcTestResult r) {
        try {
            return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 1번과 who를 친구로 */
    void befriend(long who, String studentId) {
        long id = requestId(request(1, studentId));
        assertThat(act(who, id, "accept")).hasStatusOk();
    }

    MvcTestResult book(String... participants) {
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        String ids = String.join(",", java.util.Arrays.stream(participants).map(p -> "\"" + p + "\"").toList());
        return mvc.post().uri("/api/reservations").header("X-User-Id", "1").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"%sT10:00:00+09:00","endAt":"%sT11:00:00+09:00","participantStudentIds":[%s]}"""
                        .formatted(day, day, ids))
                .exchange();
    }

    @Test
    @DisplayName("수용 기준: 학번으로 요청 → 201 PENDING, 받은 사람이 수락하면 둘 다 친구 목록에 (이름은 가림)")
    void requestThenAccept() {
        // when
        MvcTestResult sent = request(1, "20230002");

        // then: 보낸 쪽·받은 쪽 목록
        assertThat(sent).hasStatus(201).bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.status").isEqualTo("PENDING");
            j.assertThat().extractingPath("$.name").isEqualTo("홍*동");
        });
        long id = requestId(sent);
        assertThat(list(1)).bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.sent[0].studentId").isEqualTo("20230002");
            j.assertThat().extractingPath("$.friends.length()").isEqualTo(0);
        });
        assertThat(list(2)).bodyJson().extractingPath("$.received[0].requestId").isEqualTo((int) id);

        // when: 받은 사람이 수락
        assertThat(act(2, id, "accept")).hasStatusOk().bodyJson().extractingPath("$.studentId").isEqualTo("20230001");

        // then: 둘 다 친구, 대기 목록은 비어 있음
        assertThat(list(1)).bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.friends[0].studentId").isEqualTo("20230002");
            j.assertThat().extractingPath("$.friends[0].name").isEqualTo("홍*동");
            j.assertThat().extractingPath("$.sent.length()").isEqualTo(0);
        });
        assertThat(list(2)).bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.friends[0].name").isEqualTo("김*영");
            j.assertThat().extractingPath("$.received.length()").isEqualTo(0);
        });
    }

    @Test
    @DisplayName("요청 오류: 나 자신 400, 없는·인증 전 학번 404, 이미 보냄 409, 이미 친구 409, 로그인 안 함 401")
    void requestErrors() {
        assertThat(request(1, "20230001")).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("FRIEND_SELF");
        assertThat(request(1, "29999999")).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("MEMBER_NOT_FOUND");
        assertThat(request(1, "20230005")).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("MEMBER_NOT_FOUND");

        long id = requestId(request(1, "20230002"));
        assertThat(request(1, "20230002")).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("FRIEND_REQUEST_ALREADY_SENT");
        act(2, id, "accept");
        assertThat(request(1, "20230002")).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("FRIEND_ALREADY");
        assertThat(request(2, "20230001")).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("FRIEND_ALREADY");

        assertThat(mvc.get().uri("/api/friends").exchange()).hasStatus(401);
    }

    @Test
    @DisplayName("상대가 먼저 보내 둔 요청이 있으면 내 요청이 곧 수락: 200 ACCEPTED, 줄은 하나")
    void crossRequestAccepts() {
        request(2, "20230001");

        assertThat(request(1, "20230002")).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("ACCEPTED");
        assertThat(jdbc.sql("SELECT count(*) FROM friendship").query(Integer.class).single()).isEqualTo(1);
        assertThat(list(1)).bodyJson().extractingPath("$.friends[0].studentId").isEqualTo("20230002");
    }

    @Test
    @DisplayName("동시에 서로 요청해도(10번) 매번 줄 하나, 친구가 된다")
    void concurrentCrossRequests() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                jdbc.sql("DELETE FROM friendship").update();
                List<Callable<Integer>> both = List.of(
                        () -> request(1, "20230002").getResponse().getStatus(),
                        () -> request(2, "20230001").getResponse().getStatus());
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : pool.invokeAll(both)) {
                    statuses.add(f.get());
                }
                assertThat(statuses).containsExactlyInAnyOrder(201, 200);
                assertThat(jdbc.sql("SELECT status FROM friendship").query(String.class).list()).containsExactly("ACCEPTED");
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("받은 사람만 수락·거절, 보낸 사람만 취소. 남의 요청·두 번 누르기는 404. 거절하면 다시 보낼 수 있다")
    void declineAndCancel() {
        long id = requestId(request(1, "20230002"));

        assertThat(act(1, id, "accept")).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("FRIEND_REQUEST_NOT_FOUND");
        assertThat(act(3, id, "decline")).hasStatus(404);
        assertThat(act(2, id, "cancel")).hasStatus(404);

        assertThat(act(2, id, "decline")).hasStatus(204);
        assertThat(act(2, id, "decline")).hasStatus(404);
        assertThat(list(1)).bodyJson().extractingPath("$.sent.length()").isEqualTo(0);

        long again = requestId(request(1, "20230002"));
        assertThat(act(1, again, "cancel")).hasStatus(204);
        assertThat(list(2)).bodyJson().extractingPath("$.received.length()").isEqualTo(0);

        long third = requestId(request(1, "20230002"));
        act(2, third, "accept");
        assertThat(act(2, third, "accept")).hasStatus(404);   // 이미 수락
    }

    @Test
    @DisplayName("수용 기준: 친구 삭제 → 204, 양쪽 목록에서 빠짐. 친구가 아니면 404")
    void removeFriend() {
        befriend(2, "20230002");

        assertThat(remove(2, "20230001")).hasStatus(204);

        assertThat(list(1)).bodyJson().extractingPath("$.friends.length()").isEqualTo(0);
        assertThat(list(2)).bodyJson().extractingPath("$.friends.length()").isEqualTo(0);
        assertThat(remove(1, "20230002")).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("FRIEND_NOT_FOUND");
        // 대기 중 요청은 삭제로 지우지 않는다(취소·거절로)
        request(1, "20230003");
        assertThat(remove(1, "20230003")).hasStatus(404);
    }

    @Test
    @DisplayName("수용 기준: 예약 참여자는 친구만. 한 명이라도 친구가 아니면 403 PARTICIPANT_NOT_FRIEND, 대기 중 요청도 안 됨")
    void participantsMustBeFriends() {
        befriend(2, "20230002");
        long pending = requestId(request(1, "20230003"));   // 3번은 요청만 보내고 수락 전

        assertThat(book("20230002", "20230003")).hasStatus(403).bodyJson().extractingPath("$.code")
                .isEqualTo("PARTICIPANT_NOT_FRIEND");
        assertThat(book("20230003", "20230004")).hasStatus(403);
        assertThat(jdbc.sql("SELECT count(*) FROM reservation").query(Integer.class).single()).isZero();

        act(3, pending, "accept");
        assertThat(book("20230002", "20230003")).hasStatus(201);
    }

    @Test
    @DisplayName("친구를 삭제해도 이미 잡은 예약의 참여자는 그대로")
    void removingKeepsExistingReservation() {
        befriend(2, "20230002");
        befriend(3, "20230003");
        assertThat(book("20230002", "20230003")).hasStatus(201);

        remove(1, "20230002");

        assertThat(jdbc.sql("SELECT count(*) FROM reservation_participant WHERE user_id = 2 AND active")
                .query(Integer.class).single()).isEqualTo(1);
    }
}
