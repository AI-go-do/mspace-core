package kr.ac.chosun.mspace.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 신고 판정 뒤 벌칙 (FR-RPT-103·104, 11/6 지영 "docs 제안대로", 경고는 직전 이용 예약 대표자만).
 * 1번 방: 회원 2가 2~3시간 전에 쓰고 퇴실(예약 A), 지금은 회원 1이 사용 중(예약 B)이고 회원 1이 신고한다. 회원 5는 관리자.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportPenaltyTest {

    static final long REPORTER = 1;
    static final long PREVIOUS_OWNER = 2;
    static final long ADMIN = 5;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    long previous;
    long current;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE report, user_warning, report_suspension, user_suspension, notification,
                         reservation, reservation_participant, users RESTART IDENTITY CASCADE
                """).update();
        for (int i = 1; i <= 5; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "p" + i + "@chosun.ac.kr").param("s", "202500" + (10 + i)).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
        previous = tx.execute(st -> insertWithPeople("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                         checked_in_at, checked_out_at)
                SELECT 1, 2, t - interval '3 hours', t - interval '2 hours', 3, '111111', 'CHECKED_OUT',
                       t - interval '3 hours', t - interval '2 hours'
                FROM (SELECT date_trunc('hour', now()) AS t) x
                RETURNING id
                """, PREVIOUS_OWNER, 3, 4));
        current = tx.execute(st -> insertWithPeople("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status, checked_in_at)
                SELECT 1, 1, t, t + interval '1 hour', 3, '222222', 'IN_USE', t
                FROM (SELECT date_trunc('hour', now()) AS t) x
                RETURNING id
                """, REPORTER, 3, 4));
    }

    /** 예약 인원 검사 트리거가 커밋 때 돌아서, 예약과 참여자를 한 트랜잭션에 넣는다. 첫 번째가 대표자. */
    long insertWithPeople(String sql, long... userIds) {
        long id = jdbc.sql(sql).query(Long.class).single();
        for (int i = 0; i < userIds.length; i++) {
            jdbc.sql("""
                    INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                    SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                    """).param("rid", id).param("uid", userIds[i]).param("owner", i == 0).update();
        }
        return id;
    }

    long report(String category) {
        return jdbc.sql("""
                        INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category)
                        VALUES (1, 1, :rid, :category) RETURNING id
                        """)
                .param("rid", current).param("category", category).query(Long.class).single();
    }

    MvcTestResult judge(long reportId, String verdict) {
        return mvc.post().uri("/api/reports/{id}/judge", reportId).header("X-User-Id", String.valueOf(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"" + verdict + "\"}").exchange();
    }

    List<Map<String, Object>> warnings() {
        return jdbc.sql("SELECT user_id, reason, reservation_id FROM user_warning ORDER BY id").query().listOfRows();
    }

    List<Map<String, Object>> inbox(long userId) {
        return jdbc.sql("SELECT kind, title, body FROM notification WHERE user_id = :u ORDER BY id")
                .param("u", userId).query().listOfRows();
    }

    @Test
    @DisplayName("수용 기준(FR-RPT-104): 사실 판정이면 직전 이용 예약 대표자에게 경고 1회, 신고자에게는 결과 알림")
    void confirmedWarnsPreviousOwner() {
        assertThat(judge(report("MESSY"), "CONFIRMED")).hasStatusOk();

        assertThat(warnings()).singleElement().satisfies(w -> {
            assertThat(w.get("user_id")).isEqualTo(PREVIOUS_OWNER);
            assertThat(w.get("reason")).isEqualTo("VIOLATION");
            assertThat(w.get("reservation_id")).isEqualTo(previous);
        });
        assertThat(inbox(PREVIOUS_OWNER)).singleElement().satisfies(n -> {
            assertThat(n.get("kind")).isEqualTo("WARNING");
            assertThat(n.get("title")).isEqualTo("경고를 받았어요");
            assertThat((String) n.get("body")).contains("어질러짐 신고가 사실로 확인됐어요", "30일 안 경고 1회째예요");
        });
        assertThat(inbox(REPORTER)).singleElement()
                .satisfies(n -> assertThat((String) n.get("body")).contains("사실로 확인됐어요"));
    }

    @Test
    @DisplayName("그 방을 먼저 쓴 예약이 없으면 경고 없이 신고자에게 결과만 간다")
    void confirmedWithoutPreviousUse() {
        jdbc.sql("DELETE FROM reservation WHERE id = :id").param("id", previous).update();

        assertThat(judge(report("BROKEN"), "CONFIRMED")).hasStatusOk();

        assertThat(warnings()).isEmpty();
        assertThat(inbox(REPORTER)).hasSize(1);
    }

    @Test
    @DisplayName("확인 불가면 벌칙 없이 신고자에게 결과만")
    void unverifiedNoPenalty() {
        assertThat(judge(report("WINDOW_OPEN"), "UNVERIFIED")).hasStatusOk();

        assertThat(warnings()).isEmpty();
        assertThat(inbox(REPORTER)).singleElement()
                .satisfies(n -> assertThat((String) n.get("body")).contains("확인할 수 없어", "벌칙은 없어요"));
    }

    @Test
    @DisplayName("수용 기준(FR-RPT-104): 사실 판정 경고 30일 안 3번이면 대표자 14일 이용 정지")
    void threeViolationsSuspendBooking() {
        judge(report("MESSY"), "CONFIRMED");
        judge(report("BROKEN"), "CONFIRMED");
        assertThat(jdbc.sql("SELECT count(*) FROM user_suspension").query(Integer.class).single()).isZero();

        judge(report("WINDOW_OPEN"), "CONFIRMED");

        var s = jdbc.sql("""
                SELECT user_id, reason, round(extract(epoch FROM ends_at - starts_at) / 86400) AS days
                FROM user_suspension
                """).query().singleRow();
        assertThat(s.get("user_id")).isEqualTo(PREVIOUS_OWNER);
        assertThat(s.get("reason")).isEqualTo("WARNINGS");
        assertThat(((Number) s.get("days")).intValue()).isEqualTo(14);
        assertThat(inbox(PREVIOUS_OWNER)).last()
                .satisfies(n -> assertThat(n.get("title")).isEqualTo("이용이 정지됐어요"));
    }

    @Test
    @DisplayName("수용 기준(FR-RPT-103): 허위 판정이면 신고자 경고, 30일 안 3번이면 14일 신고 정지 → 새 신고 403 REPORT_SUSPENDED")
    void threeFalseReportsSuspendReporting() {
        judge(report("MESSY"), "FALSE");
        judge(report("BROKEN"), "FALSE");
        assertThat(inbox(REPORTER)).last()
                .satisfies(n -> assertThat((String) n.get("body")).contains("허위로 판정됐어요", "경고 2회째"));

        judge(report("WINDOW_OPEN"), "FALSE");

        assertThat(warnings()).extracting(w -> w.get("reason")).containsOnly("FALSE_REPORT");
        assertThat(jdbc.sql("SELECT count(*) FROM report_suspension WHERE user_id = 1").query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM user_suspension").query(Integer.class).single())
                .as("신고 정지는 예약을 막지 않는다").isZero();
        MvcTestResult again = mvc.post().uri("/api/reports").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":1,\"category\":\"BROKEN\"}").exchange();
        assertThat(again).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("REPORT_SUSPENDED");
    }

    @Test
    @DisplayName("정지가 끝난 뒤에는 정지를 건 경고까지 다시 세지 않는다: 30일 안이어도 새 경고 2번으로는 다시 정지되지 않는다")
    void warningsBeforeSuspensionAreNotRecounted() {
        judge(report("MESSY"), "CONFIRMED");
        judge(report("BROKEN"), "CONFIRMED");
        judge(report("WINDOW_OPEN"), "CONFIRMED");   // 3번째 → 정지
        // 정지가 15일 전에 걸려 어제 끝났다고 옮긴다. 경고 3번도 같은 만큼 옮겨서 아직 30일 안
        jdbc.sql("UPDATE user_suspension SET starts_at = starts_at - interval '15 days', ends_at = now() - interval '1 day'")
                .update();
        jdbc.sql("UPDATE user_warning SET created_at = created_at - interval '15 days'").update();

        judge(report("MESSY"), "CONFIRMED");
        judge(report("BROKEN"), "CONFIRMED");
        assertThat(jdbc.sql("SELECT count(*) FROM user_suspension").query(Integer.class).single())
                .as("정지 뒤 경고는 2번뿐").isEqualTo(1);
        assertThat(inbox(PREVIOUS_OWNER)).last()
                .satisfies(n -> assertThat((String) n.get("body")).contains("경고 2회째"));

        judge(report("WINDOW_OPEN"), "CONFIRMED");   // 정지 뒤 3번째 → 다시 정지
        assertThat(jdbc.sql("SELECT count(*) FROM user_suspension").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("30일보다 오래된 경고는 세지 않는다")
    void oldWarningsDoNotCount() {
        for (int i = 0; i < 2; i++) {
            long old = report("MESSY");
            jdbc.sql("""
                    INSERT INTO user_warning (user_id, reason, report_id, created_at)
                    VALUES (1, 'FALSE_REPORT', :r, now() - interval '31 days')
                    """).param("r", old).update();
        }

        judge(report("BROKEN"), "FALSE");

        assertThat(jdbc.sql("SELECT count(*) FROM report_suspension").query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("같은 사람의 허위 판정 3건을 동시에 해도 신고 정지는 정확히 1번 (사람별 잠금)")
    void concurrentJudgesSuspendOnce() throws Exception {
        List<Long> ids = List.of(report("MESSY"), report("BROKEN"), report("WINDOW_OPEN"));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (long id : ids) {
            results.add(pool.submit(() -> {
                go.await();
                return judge(id, "FALSE").getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> f : results) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdown();

        assertThat(warnings()).hasSize(3);
        assertThat(jdbc.sql("SELECT count(*) FROM report_suspension").query(Integer.class).single()).isEqualTo(1);
    }
}
