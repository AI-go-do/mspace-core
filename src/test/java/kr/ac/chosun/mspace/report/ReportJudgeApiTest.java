package kr.ac.chosun.mspace.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관리자 신고 판정 (FR-RPT-103). 회원 1·2·3이 1번 방 예약, 회원 1이 신고 3건(비품 고장, 어질러짐, 소음).
 * 회원 4는 관리자(users.role = ADMIN).
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportJudgeApiTest {

    static final long ADMIN = 4;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    long broken;
    long messy;
    long noise;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE report, reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 4; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "j" + i + "@chosun.ac.kr").param("s", "202400" + (10 + i)).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
        long reservation = tx.execute(s -> {
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                     checked_in_at)
                            SELECT 1, 1, t, t + interval '1 hour', 3, '123456', 'IN_USE', t
                            FROM (SELECT date_trunc('hour', now()) AS t) x
                            RETURNING id
                            """).query(Long.class).single();
            for (long uid = 1; uid <= 3; uid++) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                                """).param("rid", id).param("uid", uid).param("owner", uid == 1).update();
            }
            return id;
        });
        broken = report(reservation, "BROKEN", "의자 다리가 부러졌어요");
        messy = report(reservation, "MESSY", null);
        noise = report(reservation, "NOISE", null);
    }

    long report(long reservationId, String category, String content) {
        return jdbc.sql("""
                        INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category, content)
                        VALUES (1, 1, :rid, :category, :content) RETURNING id
                        """)
                .param("rid", reservationId).param("category", category).param("content", content)
                .query(Long.class).single();
    }

    MvcTestResult judge(long userId, long reportId, String verdict) {
        return mvc.post().uri("/api/reports/{id}/judge", reportId).header("X-User-Id", String.valueOf(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"" + verdict + "\"}").exchange();
    }

    MvcTestResult list(long userId, String query) {
        return mvc.get().uri("/api/reports" + query).header("X-User-Id", String.valueOf(userId)).exchange();
    }

    String verdict(long reportId) {
        return jdbc.sql("SELECT verdict FROM report WHERE id = :id").param("id", reportId).query(String.class).single();
    }

    @Test
    @DisplayName("관리자가 허위로 판정하면 200, 판정·판정한 사람·시각이 남는다")
    void adminJudges() {
        MvcTestResult r = judge(ADMIN, broken, "FALSE");

        assertThat(r).hasStatus(200);
        assertThat(r).bodyJson().extractingPath("$.verdict").isEqualTo("FALSE");
        assertThat(r).bodyJson().extractingPath("$.judgedAt").asString().endsWith("+09:00");
        var row = jdbc.sql("SELECT judged_by, judged_at IS NOT NULL AS judged FROM report WHERE id = :id")
                .param("id", broken).query().singleRow();
        assertThat(row.get("judged_by")).isEqualTo(ADMIN);
        assertThat(row.get("judged")).isEqualTo(true);
    }

    @Test
    @DisplayName("사실·확인 불가도 판정할 수 있다")
    void otherVerdicts() {
        assertThat(judge(ADMIN, broken, "CONFIRMED")).hasStatus(200);
        assertThat(judge(ADMIN, messy, "UNVERIFIED")).hasStatus(200);

        assertThat(verdict(broken)).isEqualTo("CONFIRMED");
        assertThat(verdict(messy)).isEqualTo("UNVERIFIED");
    }

    @Test
    @DisplayName("학생(관리자 아님)은 목록도 판정도 403 ADMIN_ONLY")
    void studentForbidden() {
        MvcTestResult l = list(1, "");
        MvcTestResult j = judge(2, broken, "FALSE");

        assertThat(l).hasStatus(403);
        assertThat(l).bodyJson().extractingPath("$.code").isEqualTo("ADMIN_ONLY");
        assertThat(j).hasStatus(403);
        assertThat(verdict(broken)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("목록은 최근 신고가 위, verdict로 거를 수 있다")
    void listAndFilter() {
        judge(ADMIN, broken, "CONFIRMED");

        assertThat(list(ADMIN, "")).hasStatus(200).bodyJson().extractingPath("$[*].id")
                .asArray().containsExactly((int) noise, (int) messy, (int) broken);
        assertThat(list(ADMIN, "?verdict=OPEN")).bodyJson().extractingPath("$[*].id")
                .asArray().containsExactly((int) noise, (int) messy);
        assertThat(list(ADMIN, "?verdict=CONFIRMED")).bodyJson().extractingPath("$[0].content")
                .isEqualTo("의자 다리가 부러졌어요");
        assertThat(list(ADMIN, "?verdict=MAYBE")).hasStatus(400);
    }

    @Test
    @DisplayName("소음은 판정 대상이 아니라 어떤 판정이든 409 REPORT_NOT_JUDGEABLE")
    void noiseNotJudgeable() {
        for (String v : new String[] {"FALSE", "CONFIRMED", "UNVERIFIED"}) {
            MvcTestResult r = judge(ADMIN, noise, v);
            assertThat(r).hasStatus(409);
            assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("REPORT_NOT_JUDGEABLE");
        }
        assertThat(verdict(noise)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("판정은 한 번만: 두 번째는 409 REPORT_ALREADY_JUDGED이고 처음 판정이 남는다")
    void judgeOnce() {
        judge(ADMIN, broken, "CONFIRMED");

        MvcTestResult again = judge(ADMIN, broken, "FALSE");

        assertThat(again).hasStatus(409);
        assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("REPORT_ALREADY_JUDGED");
        assertThat(verdict(broken)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("없는 신고 404, 판정 값이 OPEN·없는 값·빠짐이면 400")
    void notFoundAndInvalid() {
        assertThat(judge(ADMIN, 9999, "FALSE")).hasStatus(404).bodyJson().extractingPath("$.code")
                .isEqualTo("REPORT_NOT_FOUND");
        assertThat(judge(ADMIN, broken, "OPEN")).hasStatus(400);
        assertThat(judge(ADMIN, broken, "MAYBE")).hasStatus(400);
        assertThat(mvc.post().uri("/api/reports/{id}/judge", broken).header("X-User-Id", String.valueOf(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(400);
        assertThat(verdict(broken)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("같은 신고를 동시에 10번 판정해도 200은 1번, 나머지는 409")
    void concurrentJudge() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String v = i % 2 == 0 ? "CONFIRMED" : "FALSE";
            results.add(pool.submit(() -> {
                start.await();
                return judge(ADMIN, broken, v).getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(9);
    }
}
