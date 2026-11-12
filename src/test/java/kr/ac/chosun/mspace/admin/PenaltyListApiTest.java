package kr.ac.chosun.mspace.admin;

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

/** 관리자 경고·정지 목록 (GET /api/penalties). 회원 1·2·3 학생, 4 관리자 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PenaltyListApiTest {

    static final long ADMIN = 4;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE report_suspension, user_suspension, user_warning, report, reservation, reservation_participant,
                         users RESTART IDENTITY CASCADE
                """).update();
        for (int i = 1; i <= 4; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, :n, now())")
                    .param("e", "p" + i + "@chosun.ac.kr").param("s", "202400" + (40 + i)).param("n", "회원" + i).update();
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
        for (int i = 0; i < 3; i++) {
            jdbc.sql("""
                    INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category)
                    VALUES (1, 1, :rid, 'MESSY')
                    """).param("rid", reservation).update();
        }
        // 경고: 2번 회원 위반(오늘), 1번 회원 허위(오늘), 2번 회원 위반(40일 전)
        jdbc.sql("""
                INSERT INTO user_warning (user_id, reason, report_id, reservation_id, created_at) VALUES
                    (2, 'VIOLATION', 1, :rid, now() - interval '1 hour'),
                    (1, 'FALSE_REPORT', 2, NULL, now()),
                    (2, 'VIOLATION', 3, :rid, now() - interval '40 days')
                """).param("rid", reservation).update();
        // 정지: 2번 이용 정지 진행 중, 3번 노쇼 정지 50일 전에 끝남, 1번 신고 정지 진행 중
        jdbc.sql("""
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at, warning_id) VALUES
                    (2, 'WARNINGS', now() - interval '1 hour', now() + interval '14 days', 1),
                    (3, 'NO_SHOW', now() - interval '64 days', now() - interval '50 days', NULL)
                """).update();
        jdbc.sql("""
                INSERT INTO report_suspension (user_id, starts_at, ends_at, warning_id)
                VALUES (1, now(), now() + interval '14 days', 2)
                """).update();
    }

    MvcTestResult list(long userId, String query) {
        return mvc.get().uri("/api/penalties" + query).header("X-User-Id", String.valueOf(userId)).exchange();
    }

    @Test
    @DisplayName("기본 30일: 최근 경고 2건(최근 것부터)과 진행 중 정지 2건, 학번·이름이 보인다")
    void defaultThirtyDays() {
        MvcTestResult r = list(ADMIN, "");

        assertThat(r).hasStatus(200);
        var json = assertThat(r).bodyJson();
        json.extractingPath("$.days").isEqualTo(30);
        json.extractingPath("$.warnings.length()").isEqualTo(2);
        json.extractingPath("$.warnings[0].reason").isEqualTo("FALSE_REPORT");
        json.extractingPath("$.warnings[0].member.studentId").isEqualTo("20240041");
        json.extractingPath("$.warnings[0].reservationId").isNull();
        json.extractingPath("$.warnings[1].reason").isEqualTo("VIOLATION");
        json.extractingPath("$.warnings[1].reservationId").isEqualTo(1);
        json.extractingPath("$.warnings[1].createdAt").asString().endsWith("+09:00");
        json.extractingPath("$.warnings[1].withdrawnAt").isNull();
        json.extractingPath("$.suspensions.length()").isEqualTo(2);
        json.extractingPath("$.suspensions[0].scope").isEqualTo("REPORT");
        json.extractingPath("$.suspensions[0].reason").isEqualTo("FALSE_REPORT");
        json.extractingPath("$.suspensions[0].active").isEqualTo(true);
        json.extractingPath("$.suspensions[1].scope").isEqualTo("USE");
        json.extractingPath("$.suspensions[1].reason").isEqualTo("WARNINGS");
        json.extractingPath("$.suspensions[1].member.name").isEqualTo("회원2");
    }

    @Test
    @DisplayName("철회된 경고도 목록에 남고 withdrawnAt(+09:00)이 붙는다")
    void withdrawnWarningShowsTime() {
        // given: 2번 회원의 오늘 위반 경고가 철회됨
        jdbc.sql("UPDATE user_warning SET withdrawn_at = now() WHERE id = 1").update();

        // when
        var json = assertThat(list(ADMIN, "")).bodyJson();

        // then
        json.extractingPath("$.warnings.length()").isEqualTo(2);
        json.extractingPath("$.warnings[1].id").isEqualTo(1);
        java.time.Instant saved = jdbc.sql("SELECT withdrawn_at FROM user_warning WHERE id = 1")
                .query(java.sql.Timestamp.class).single().toInstant();
        json.extractingPath("$.warnings[1].withdrawnAt").asString().endsWith("+09:00")
                .satisfies(t -> assertThat(java.time.OffsetDateTime.parse(t).toInstant()).isEqualTo(saved));
        json.extractingPath("$.warnings[0].withdrawnAt").isNull();
    }

    @Test
    @DisplayName("days=60이면 40일 전 경고와 50일 전에 끝난 정지(active false)도 나온다")
    void longerWindow() {
        var json = assertThat(list(ADMIN, "?days=60")).bodyJson();

        json.extractingPath("$.warnings.length()").isEqualTo(3);
        json.extractingPath("$.suspensions.length()").isEqualTo(3);
        json.extractingPath("$.suspensions[2].reason").isEqualTo("NO_SHOW");
        json.extractingPath("$.suspensions[2].active").isEqualTo(false);
    }

    @Test
    @DisplayName("학생은 403, days가 0이거나 365를 넘으면 400")
    void forbiddenAndBadDays() {
        assertThat(list(1, "")).hasStatus(403);
        assertThat(list(ADMIN, "?days=0")).hasStatus(400);
        assertThat(list(ADMIN, "?days=366")).hasStatus(400);
    }
}
