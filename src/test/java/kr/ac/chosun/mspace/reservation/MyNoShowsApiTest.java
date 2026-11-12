package kr.ac.chosun.mspace.reservation;

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

/**
 * 내 노쇼 기록 (GET /api/penalties/me/no-shows, GET /api/penalties/me의 noShows, FR-RSV-106, 11/12 지영).
 * 정지를 거는 셈과 같은 기준인지 본다. 회원 1·2. 기록은 지난 예약을 바로 넣는다 (끝 = 기록 시각)
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MyNoShowsApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE user_suspension, reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "n" + i + "@chosun.ac.kr").param("s", "202400" + (70 + i)).update();
        }
    }

    /** endedAgo 전에 끝난 1시간짜리 예약 (회원 1·2·3이 같이, owner가 대표자). 참여자도 같은 문장에서 넣어 인원 검사를 통과 */
    long past(long owner, int room, String status, String endedAgo) {
        boolean away = "AWAY_RELEASED".equals(status);
        return jdbc.sql("""
                        WITH r AS (
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                     checked_in_at, checked_out_at)
                            SELECT :room, :owner, e - interval '1 hour', e, 3, '123456', :status,
                                   CASE WHEN :away THEN e - interval '1 hour' END, CASE WHEN :away THEN e END
                            FROM (SELECT date_trunc('hour', now()) - CAST(:ago AS interval) AS e) x
                            RETURNING id, owner_id, start_at, end_at),
                        p AS (
                            INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                            SELECT r.id, u, u = r.owner_id, r.start_at, r.end_at FROM r, generate_series(1, 3) u)
                        SELECT id FROM r
                        """)
                .param("room", room).param("owner", owner).param("status", status).param("away", away)
                .param("ago", endedAgo).query(Long.class).single();
    }

    MvcTestResult list(long user, String query) {
        return mvc.get().uri("/api/penalties/me/no-shows" + query).header("X-User-Id", String.valueOf(user)).exchange();
    }

    @Test
    @DisplayName("셈에 들어가는 노쇼·자리 비움 수를 /me에 주고, 무른 노쇼·30일 밖·남의 예약은 안 센다")
    void summaryMatchesSuspensionRule() {
        past(1, 1, "NO_SHOW", "2 days");
        long excused = past(1, 2, "NO_SHOW", "1 day");
        jdbc.sql("UPDATE reservation SET no_show_excused_at = now() WHERE id = :id").param("id", excused).update();
        past(1, 3, "AWAY_RELEASED", "3 hours");
        past(1, 4, "NO_SHOW", "40 days");
        past(2, 2, "NO_SHOW", "1 hour");

        var json = assertThat(mvc.get().uri("/api/penalties/me").header("X-User-Id", "1").exchange()).bodyJson();
        json.extractingPath("$.noShows.noShows").isEqualTo(1);
        json.extractingPath("$.noShows.awayReleases").isEqualTo(1);
        json.extractingPath("$.noShows.limit").isEqualTo(2);
        json.extractingPath("$.noShows.awayPerNoShow").isEqualTo(3);
        json.extractingPath("$.noShows.windowDays").isEqualTo(30);
        json.extractingPath("$.warnings").isEqualTo(0);
    }

    @Test
    @DisplayName("목록은 최근 것부터, 무른 노쇼는 counted=false와 excusedAt(+09:00). 기본 30일이라 40일 전 것은 없다")
    void listNewestFirst() {
        past(1, 1, "NO_SHOW", "2 days");
        long excused = past(1, 2, "NO_SHOW", "1 day");
        jdbc.sql("UPDATE reservation SET no_show_excused_at = now() WHERE id = :id").param("id", excused).update();
        long away = past(1, 3, "AWAY_RELEASED", "3 hours");
        past(1, 4, "NO_SHOW", "40 days");

        MvcTestResult r = list(1, "");

        assertThat(r).hasStatus(200);
        var json = assertThat(r).bodyJson();
        json.extractingPath("$.items.length()").isEqualTo(3);
        json.extractingPath("$.items[0].reservationId").isEqualTo((int) away);
        json.extractingPath("$.items[0].kind").isEqualTo("AWAY_RELEASED");
        json.extractingPath("$.items[0].roomName").isNotNull();
        json.extractingPath("$.items[0].happenedAt").asString().endsWith("+09:00");
        json.extractingPath("$.items[1].reservationId").isEqualTo((int) excused);
        json.extractingPath("$.items[1].counted").isEqualTo(false);
        json.extractingPath("$.items[1].excusedAt").asString().endsWith("+09:00");
        json.extractingPath("$.items[2].counted").isEqualTo(true);
        json.extractingPath("$.items[2].excusedAt").isNull();
    }

    @Test
    @DisplayName("days=60이면 40일 전 노쇼도 보이지만 30일 밖이라 counted=false")
    void olderShownButNotCounted() {
        past(1, 4, "NO_SHOW", "40 days");

        var json = assertThat(list(1, "?days=60")).bodyJson();
        json.extractingPath("$.items.length()").isEqualTo(1);
        json.extractingPath("$.items[0].counted").isEqualTo(false);
    }

    @Test
    @DisplayName("노쇼 정지가 끝나기 전 기록은 다시 세지 않는다 (정지가 끝나면 처음부터)")
    void beforeLastSuspensionNotCounted() {
        past(1, 1, "NO_SHOW", "20 days");
        past(1, 2, "NO_SHOW", "19 days");
        jdbc.sql("""
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at)
                VALUES (1, 'NO_SHOW', now() - interval '19 days', now() - interval '5 days')
                """).update();
        past(1, 3, "NO_SHOW", "1 day");

        assertThat(mvc.get().uri("/api/penalties/me").header("X-User-Id", "1").exchange()).bodyJson()
                .extractingPath("$.noShows.noShows").isEqualTo(1);
        var json = assertThat(list(1, "")).bodyJson();
        json.extractingPath("$.items[0].counted").isEqualTo(true);
        json.extractingPath("$.items[1].counted").isEqualTo(false);
        json.extractingPath("$.items[2].counted").isEqualTo(false);
    }

    @Test
    @DisplayName("days가 1–90 밖이면 400 INVALID_REQUEST, 로그인 안 하면 401")
    void badDays() {
        assertThat(list(1, "?days=0")).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(list(1, "?days=91")).hasStatus(400);
        assertThat(mvc.get().uri("/api/penalties/me/no-shows").exchange()).hasStatus(401);
    }

    @Test
    @DisplayName("사람마다 다른 벌칙 응답 셋(노쇼 목록·내 벌칙·내 경고)은 저장 금지(Cache-Control: no-store)")
    void noStore() {
        for (String uri : new String[] {"/api/penalties/me/no-shows", "/api/penalties/me", "/api/penalties/me/warnings"}) {
            assertThat(mvc.get().uri(uri).header("X-User-Id", "1").exchange())
                    .hasStatus(200).headers().hasValue("Cache-Control", "no-store");
        }
    }
}
