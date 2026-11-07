package kr.ac.chosun.mspace.report;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.chosun.mspace.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionOperations;

/** 내 경고 횟수 (GET /api/penalties/me, FR-WEB-003). 회원 1·2·3 학생 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MyPenaltyApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    long reservation;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE report_suspension, user_suspension, user_warning, report, reservation, reservation_participant,
                         users RESTART IDENTITY CASCADE
                """).update();
        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "m" + i + "@chosun.ac.kr").param("s", "202400" + (50 + i)).update();
        }
        reservation = tx.execute(st -> {
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                     checked_in_at, checked_out_at)
                            SELECT 1, 1, t - interval '3 hours', t - interval '2 hours', 3, '123456', 'CHECKED_OUT',
                                   t - interval '3 hours', t - interval '2 hours'
                            FROM (SELECT date_trunc('hour', now()) AS t) x
                            RETURNING id
                            """).query((rs, n) -> rs.getLong(1)).single();
            for (long uid = 1; uid <= 3; uid++) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                                """).param("rid", id).param("uid", uid).param("owner", uid == 1).update();
            }
            return id;
        });
        for (int i = 0; i < 5; i++) {
            jdbc.sql("INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category) VALUES (1, 1, :rid, 'MESSY')")
                    .param("rid", reservation).update();
        }
    }

    void warn(long userId, String reason, long reportId, String ago) {
        jdbc.sql("""
                        INSERT INTO user_warning (user_id, reason, report_id, reservation_id, created_at)
                        VALUES (:u, :r, :rep, :rid, now() - CAST(:ago AS interval))
                        """)
                .param("u", userId).param("r", reason).param("rep", reportId)
                .param("rid", "VIOLATION".equals(reason) ? reservation : null).param("ago", ago).update();
    }

    MvcTestResult mine(long userId) {
        return mvc.get().uri("/api/penalties/me").header("X-User-Id", String.valueOf(userId)).exchange();
    }

    @Test
    @DisplayName("경고가 없으면 0회, 정지 없음, 기준 숫자(3회·30일)를 같이 준다")
    void none() {
        MvcTestResult r = mine(3);

        assertThat(r).hasStatus(200);
        var json = assertThat(r).bodyJson();
        json.extractingPath("$.warnings").isEqualTo(0);
        json.extractingPath("$.falseReportWarnings").isEqualTo(0);
        json.extractingPath("$.limit").isEqualTo(3);
        json.extractingPath("$.windowDays").isEqualTo(30);
        json.extractingPath("$.suspendedUntil").isNull();
        json.extractingPath("$.reportSuspendedUntil").isNull();
    }

    @Test
    @DisplayName("30일 안 위반 경고 2번이면 2회. 40일 전 경고와 허위 신고 경고는 위반 횟수에 안 들어간다")
    void countsOnlyRecentViolations() {
        warn(1, "VIOLATION", 1, "1 day");
        warn(1, "VIOLATION", 2, "2 hours");
        warn(1, "VIOLATION", 3, "40 days");
        warn(1, "FALSE_REPORT", 4, "1 hour");

        var json = assertThat(mine(1)).bodyJson();
        json.extractingPath("$.warnings").isEqualTo(2);
        json.extractingPath("$.falseReportWarnings").isEqualTo(1);
    }

    @Test
    @DisplayName("경고 3번으로 정지되면 정지 끝나는 시각(+09:00)을 주고, 정지를 건 경고까지는 다시 세지 않는다")
    void suspended() {
        warn(2, "VIOLATION", 1, "3 hours");
        warn(2, "VIOLATION", 2, "2 hours");
        warn(2, "VIOLATION", 3, "1 hour");
        jdbc.sql("""
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at, warning_id)
                VALUES (2, 'WARNINGS', now() - interval '1 hour', now() + interval '14 days', 3)
                """).update();

        var json = assertThat(mine(2)).bodyJson();
        json.extractingPath("$.warnings").isEqualTo(0);
        json.extractingPath("$.suspendedUntil").asString().endsWith("+09:00");
        json.extractingPath("$.reportSuspendedUntil").isNull();
    }

    @Test
    @DisplayName("노쇼 정지 중이어도 정지 끝나는 시각을 주고, 끝난 정지는 주지 않는다")
    void noShowSuspension() {
        jdbc.sql("""
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at) VALUES
                    (3, 'NO_SHOW', now() - interval '64 days', now() - interval '50 days'),
                    (2, 'NO_SHOW', now() - interval '1 day', now() + interval '13 days')
                """).update();

        assertThat(mine(3)).bodyJson().extractingPath("$.suspendedUntil").isNull();
        assertThat(mine(2)).bodyJson().extractingPath("$.suspendedUntil").asString().endsWith("+09:00");
    }

    @Test
    @DisplayName("신고 정지 중이면 신고 정지 끝나는 시각을 주고, 이용 정지는 없음")
    void reportSuspended() {
        warn(1, "FALSE_REPORT", 1, "1 hour");
        jdbc.sql("""
                INSERT INTO report_suspension (user_id, starts_at, ends_at, warning_id)
                VALUES (1, now() - interval '1 hour', now() + interval '14 days', 1)
                """).update();

        var json = assertThat(mine(1)).bodyJson();
        json.extractingPath("$.falseReportWarnings").isEqualTo(0);
        json.extractingPath("$.reportSuspendedUntil").asString().endsWith("+09:00");
        json.extractingPath("$.suspendedUntil").isNull();
    }

    @Test
    @DisplayName("남의 경고는 보이지 않는다: 1번 회원 경고가 있어도 2번 회원은 0회")
    void onlyMine() {
        warn(1, "VIOLATION", 1, "1 hour");

        assertThat(mine(2)).bodyJson().extractingPath("$.warnings").isEqualTo(0);
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void unauthorized() {
        assertThat(mvc.get().uri("/api/penalties/me").exchange()).hasStatus(401);
    }
}
