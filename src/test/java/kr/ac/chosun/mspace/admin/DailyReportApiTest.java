package kr.ac.chosun.mspace.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 관리자 하루 운영 보고 (GET /api/admin/daily-report). 테스트는 Gemini 키가 비어 있어 정해진 틀(template)로 쓴다.
 * 회원 1–3 학생, 4 관리자. 어제(서울) 하루에 예약·신고·경고를 넣는다.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DailyReportApiTest.FixedClock.class})
class DailyReportApiTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 서울 2026-11-12 12:00 */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), SEOUL);
    static final long ADMIN = 4;
    static final LocalDate TODAY = LocalDate.now(CLOCK);
    static final LocalDate DAY = TODAY.minusDays(1);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    org.springframework.transaction.support.TransactionOperations tx;

    static Timestamp at(LocalDate d, int h) {
        return Timestamp.from(d.atTime(h, 0).atZone(SEOUL).toInstant());
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE daily_report, report_judgement, warning_appeal, user_warning, report, device_fault, sensor_event,
                         notification, reservation_participant, reservation, users RESTART IDENTITY CASCADE
                """).update();
        for (int i = 1; i <= 4; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "d" + i + "@chosun.ac.kr").param("s", "202400" + (80 + i)).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
        // 어제 1번 방: 10–12시 이용, 14–15시 노쇼
        long used = reservation(1, 10, 12, "CHECKED_OUT", at(DAY, 10), at(DAY, 12));
        reservation(2, 14, 15, "NO_SHOW", null, null);
        // 어제 신고 2건(어질러짐 확인 불가 + 창문 대기), 경고 1건
        long messy = jdbc.sql("""
                INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category, content, verdict, judged_at, created_at)
                VALUES (1, 3, :rid, 'MESSY', '컵', 'CONFIRMED', :at, :at) RETURNING id
                """).param("rid", used).param("at", at(DAY, 12)).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category, created_at)
                VALUES (1, 3, :rid, 'WINDOW_OPEN', :at)
                """).param("rid", used).param("at", at(DAY, 13)).update();
        jdbc.sql("INSERT INTO user_warning (user_id, reason, report_id, reservation_id, created_at) VALUES (1, 'VIOLATION', :r, :rid, :at)")
                .param("r", messy).param("rid", used).param("at", at(DAY, 12)).update();
    }

    long reservation(long owner, int from, int to, String status, Timestamp in, Timestamp out) {
        return tx.execute(st -> {
            long id = jdbc.sql("""
                        INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                 checked_in_at, checked_out_at, created_at)
                        VALUES (1, :owner, :start, :end, 3, '123456', :status, :in, :out, CAST(:start AS timestamptz) - interval '1 day')
                        RETURNING id
                        """)
                .param("owner", owner).param("start", at(DAY, from)).param("end", at(DAY, to)).param("status", status)
                .param("in", in).param("out", out).query(Long.class).single();
            for (long uid = owner; uid < owner + 3; uid++) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                                """).param("rid", id).param("uid", uid).param("owner", uid == owner).update();
            }
            return id;
        });
    }

    MvcTestResult get(long user, String query) {
        return mvc.get().uri("/api/admin/daily-report" + query).header("X-User-Id", String.valueOf(user)).exchange();
    }

    @Test
    @DisplayName("관리자는 하루 숫자와 3–5줄 요약을 받는다. 키가 없으면 정해진 틀로 쓰고, 이름은 들어가지 않는다")
    void reportForDay() throws Exception {
        // when
        MvcTestResult r = get(ADMIN, "?date=" + DAY);

        // then
        assertThat(r).hasStatusOk();
        assertThat(r).bodyJson().extractingPath("$.date").isEqualTo(DAY.toString());
        assertThat(r).bodyJson().extractingPath("$.writer").isEqualTo("template");
        assertThat(r).bodyJson().extractingPath("$.facts.total.reservations.total").isEqualTo(2);
        assertThat(r).bodyJson().extractingPath("$.facts.total.reservations.noShow").isEqualTo(1);
        assertThat(r).bodyJson().extractingPath("$.facts.reports.MESSY.CONFIRMED").isEqualTo(1);
        assertThat(r).bodyJson().extractingPath("$.facts.reports.WINDOW_OPEN.OPEN").isEqualTo(1);
        assertThat(r).bodyJson().extractingPath("$.facts.warnings.VIOLATION").isEqualTo(1);
        assertThat(r).bodyJson().extractingPath("$.facts.partial").isEqualTo(false);
        assertThat(r).bodyJson().extractingPath("$.lines[0]").asString()
                .isEqualTo("11월 11일 예약 2건 중 1건 이용, 노쇼 1건, 취소 0건이에요.");
        assertThat(r).bodyJson().extractingPath("$.lines[2]").asString().isEqualTo("신고 2건(사실 1, 대기 1), 경고 1건이에요.");
        assertThat(r.getResponse().getContentAsString()).doesNotContain("홍길동").doesNotContain("2024008");
    }

    /** 보관된 요약을 알아볼 수 있는 글로 바꾼다 (지문·쓴 사람·쓴 시각은 인자로) */
    void stored(LocalDate date, String first, String writer, String hash, Instant at) {
        jdbc.sql("""
                UPDATE daily_report SET lines = ARRAY[:first, '둘째 줄', '셋째 줄'], writer = :writer,
                       facts_hash = COALESCE(:hash, facts_hash), generated_at = :at
                WHERE report_date = :d
                """).param("first", first).param("writer", writer).param("hash", hash).param("at", Timestamp.from(at))
                .param("d", java.sql.Date.valueOf(date)).update();
    }

    /** 요약 첫 줄 */
    String first(String query) throws Exception {
        String body = get(ADMIN, query).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(body).path("lines").path(0).asString();
    }

    @Test
    @DisplayName("지난 날 요약은 숫자가 같으면 다시 쓰지 않고, 숫자가 바뀌었거나 Gemini 실패로 대신 쓴 글이면 새로 쓴다")
    void pastDayCache() throws Exception {
        // given: 한 번 열어 지금 숫자의 지문으로 보관된 요약
        get(ADMIN, "?date=" + DAY);
        Instant longAgo = CLOCK.instant().minusSeconds(86_400);
        stored(DAY, "보관된 요약", "gemini:test", null, longAgo);

        // when / then: 숫자가 같으면 오래됐어도 그대로
        assertThat(first("?date=" + DAY)).isEqualTo("보관된 요약");

        // given: 요약을 쓴 뒤 숫자가 바뀜(늦게 정해진 판정 등)
        stored(DAY, "옛 숫자로 쓴 요약", "gemini:test", "changed", longAgo);

        // when / then: 새로 쓴다
        assertThat(first("?date=" + DAY)).startsWith("11월 11일 예약 2건");
        assertThat(jdbc.sql("SELECT writer FROM daily_report").query(String.class).single()).isEqualTo("template");

        // given: Gemini 실패로 정해진 틀로 대신 쓴 글, 30분 전
        stored(DAY, "대신 쓴 요약", "template:fallback", null, CLOCK.instant().minusSeconds(1800));

        // when / then: 1시간 안이면 그대로(실패 직후 또 부르지 않음), 지나면 다시 써 본다
        assertThat(first("?date=" + DAY)).isEqualTo("대신 쓴 요약");
        stored(DAY, "대신 쓴 요약", "template:fallback", null, CLOCK.instant().minusSeconds(7200));
        assertThat(first("?date=" + DAY)).startsWith("11월 11일 예약 2건");
    }

    @Test
    @DisplayName("오늘 요약은 숫자가 바뀌어도 1시간 안이면 보관된 것을, 지나면 새로 쓴다. 숫자가 같으면 그대로. 날짜를 빼면 오늘")
    void todayRefresh() throws Exception {
        // given: 숫자가 바뀐 뒤의 30분 전 요약
        get(ADMIN, "");
        stored(TODAY, "30분 전 요약", "gemini:test", "changed", CLOCK.instant().minusSeconds(1800));

        // when / then
        MvcTestResult fresh = get(ADMIN, "");
        assertThat(fresh).bodyJson().extractingPath("$.lines[0]").isEqualTo("30분 전 요약");
        assertThat(fresh).bodyJson().extractingPath("$.facts.partial").isEqualTo(true);
        // 어제(DAY) 예약·신고·경고는 오늘 숫자에 섞이지 않는다
        assertThat(fresh).bodyJson().extractingPath("$.facts.total.reservations.total").isEqualTo(0);
        assertThat(fresh).bodyJson().extractingPath("$.facts.reports").asMap().isEmpty();
        assertThat(fresh).bodyJson().extractingPath("$.facts.warnings").asMap().isEmpty();

        // given: 숫자가 바뀐 채 2시간 전 요약 → 새로 쓴다
        stored(TODAY, "2시간 전 요약", "gemini:test", "changed", CLOCK.instant().minusSeconds(7200));
        assertThat(get(ADMIN, "")).bodyJson().extractingPath("$.writer").isEqualTo("template");

        // given: 방금 새로 쓰며 지금 지문이 보관됨. 2시간 전으로 돌려도 숫자가 같으면 그대로
        stored(TODAY, "2시간 전 요약", "gemini:test", null, CLOCK.instant().minusSeconds(7200));
        assertThat(first("")).isEqualTo("2시간 전 요약");
    }

    @Test
    @DisplayName("관리자가 아니면 403, 내일 날짜면 400")
    void forbiddenAndFuture() {
        assertThat(get(1, "?date=" + DAY)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get(ADMIN, "?date=" + TODAY.plusDays(1))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(ADMIN, "?date=어제")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
