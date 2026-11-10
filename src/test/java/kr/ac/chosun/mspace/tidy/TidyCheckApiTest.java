package kr.ac.chosun.mspace.tidy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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
 * 퇴실 뒤 정리 확인 (FR-DEV-105). 11/10 10차 회의·지영: 종류 구분 없이 "두고 감"(LEFT_ITEMS),
 * 퇴실 직후(FIRST)는 알림만, 3시간 뒤 다시 봐도(RECHECK) 확신도 0.8 이상 물건이 남으면 core가 바로 대표자 경고.
 * 1번 방: 회원 1(대표자)·2·3이 2~1시간 전에 쓰고 퇴실(ended), 지금은 회원 4가 사용 중(now). 회원 5는 관리자.
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TidyCheckApiTest {

    static final String TOKEN = "Bearer test-device-token";
    static final long OWNER = 1;
    static final long ADMIN = 5;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    long ended;
    long now;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE tidy_check, report, user_warning, report_suspension, user_suspension, notification,
                         reservation, reservation_participant, users RESTART IDENTITY CASCADE
                """).update();
        for (int i = 1; i <= 7; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "t" + i + "@chosun.ac.kr").param("s", "202600" + (10 + i)).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
        ended = tx.execute(st -> insertWithPeople("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                         checked_in_at, checked_out_at)
                SELECT 1, 1, t - interval '2 hours', t - interval '1 hour', 3, '111111', 'CHECKED_OUT',
                       t - interval '2 hours', t - interval '1 hour'
                FROM (SELECT date_trunc('hour', now()) AS t) x
                RETURNING id
                """, OWNER, 2, 3));
        now = tx.execute(st -> insertWithPeople("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status, checked_in_at)
                SELECT 1, 4, t, t + interval '1 hour', 3, '222222', 'IN_USE', t
                FROM (SELECT date_trunc('hour', now()) AS t) x
                RETURNING id
                """, 4, 6, 7));
    }

    /** 예약 인원 검사 트리거가 커밋 때 돌아서, 예약과 참여자를 한 트랜잭션에 넣는다. 첫 번째가 대표자 */
    long insertWithPeople(String sql, long... userIds) {
        long id = jdbc.sql(sql).query((rs, n) -> rs.getLong(1)).single();
        for (int i = 0; i < userIds.length; i++) {
            jdbc.sql("""
                    INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                    SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                    """).param("rid", id).param("uid", userIds[i]).param("owner", i == 0).update();
        }
        return id;
    }

    MvcTestResult post(int roomId, String json) {
        return mvc.post().uri("/api/rooms/{id}/tidy-checks", roomId).header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    /** items 예: [{"name":"umbrella","confidence":0.93}] */
    /** FIRST는 고정 시각, RECHECK는 지금 본 것으로 (기한 뒤에 본 화면만 받으므로) */
    String check(String eventId, long reservationId, String phase, String result, String items) {
        String observed = "RECHECK".equals(phase)
                ? OffsetDateTime.now(ZoneOffset.ofHours(9)).withNano(0).toString()
                : "2026-11-06T15:01:00+09:00";
        return """
                {"eventId":"%s","reservationId":%d,"deviceId":"jetson-b","phase":"%s","result":"%s",
                 "items":%s,"observedAt":"%s"}"""
                .formatted(eventId, reservationId, phase, result, items, observed);
    }

    String first(String eventId, long reservationId, String result, String items) {
        return check(eventId, reservationId, "FIRST", result, items);
    }

    static final String UMBRELLA = "[{\"name\":\"umbrella\",\"confidence\":0.93}]";

    static String body(MvcTestResult r) {
        try {
            return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    List<Map<String, Object>> reports() {
        return jdbc.sql("""
                SELECT id, source, category, verdict, judged_by, judged_at, reporter_id, target_reservation_id, content
                FROM report ORDER BY id
                """).query().listOfRows();
    }

    List<Map<String, Object>> inbox(String kind) {
        return jdbc.sql("SELECT user_id, title, body FROM notification WHERE kind = :k ORDER BY user_id")
                .param("k", kind).query().listOfRows();
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query((rs, n) -> rs.getLong(1)).single();
    }

    MvcTestResult current(int roomId) {
        return mvc.get().uri("/api/rooms/{id}/current", roomId).header("Authorization", TOKEN).exchange();
    }

    /** 다시 볼 시각이 지난 상태: ended는 4시간 전에 퇴실, now는 3번 방으로 옮겨 1번 방이 빔 */
    void recheckDue() {
        jdbc.sql("UPDATE reservation SET checked_out_at = now() - interval '4 hours' WHERE id = :id")
                .param("id", ended).update();
        roomFree();
    }

    void roomFree() {
        // 상태는 IN_USE → BOOKED로 되돌릴 수 없어(트리거) 방을 옮긴다
        jdbc.sql("UPDATE reservation SET room_id = 3 WHERE id = :id").param("id", now).update();
    }

    @Test
    @DisplayName("방 지금 예약: 사용 중인 예약과 방금 끝난 예약 번호를 준다. 다시 볼 것이 없으면 recheck null")
    void currentShowsInUseAndLastEnded() {
        MvcTestResult r = current(1);

        assertThat(r).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.roomId").isEqualTo(1);
            j.assertThat().extractingPath("$.current.reservationId").isEqualTo((int) now);
            j.assertThat().extractingPath("$.current.status").isEqualTo("IN_USE");
            j.assertThat().extractingPath("$.lastEnded.reservationId").isEqualTo((int) ended);
            j.assertThat().extractingPath("$.lastEnded.status").isEqualTo("CHECKED_OUT");
            j.assertThat().extractingPath("$.recheck").isNull();
        });
        assertThat(body(r)).contains("+09:00");
    }

    @Test
    @DisplayName("방 지금 예약: 빈 방이면 current·lastEnded·recheck 모두 null")
    void currentEmptyRoom() {
        assertThat(current(2)).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.current").isNull();
            j.assertThat().extractingPath("$.lastEnded").isNull();
            j.assertThat().extractingPath("$.recheck").isNull();
        });
    }

    @Test
    @DisplayName("장치 토큰이 없거나 틀리면 401, 없는 방은 404")
    void tokenAndRoom() {
        assertThat(mvc.get().uri("/api/rooms/1/current")).hasStatus(401);
        assertThat(mvc.get().uri("/api/rooms/1/current").header("Authorization", "Bearer wrong")).hasStatus(401);
        assertThat(mvc.post().uri("/api/rooms/1/tidy-checks").contentType(MediaType.APPLICATION_JSON)
                .content(first("e0", ended, "CLEAN", "[]"))).hasStatus(401);
        assertThat(current(99)).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    @DisplayName("수용 기준: 깨끗하면 결과만 남고 신고·알림·다시 확인은 없다")
    void cleanStoresOnly() {
        assertThat(post(1, first("e1", ended, "CLEAN", "[]"))).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.result").isEqualTo("STORED");
            j.assertThat().extractingPath("$.reportId").isNull();
        });
        roomFree();

        assertThat(reports()).isEmpty();
        assertThat(inbox("LEFT_ITEM")).isEmpty();
        assertThat(current(1)).bodyJson().extractingPath("$.recheck").isNull();
        assertThat(jdbc.sql("SELECT phase || ':' || result FROM tidy_check").query((rs, n) -> rs.getString(1)).list())
                .containsExactly("FIRST:CLEAN");
    }

    @Test
    @DisplayName("수용 기준: 퇴실 직후 두고 간 물건(종류 상관없이)이 있으면 대표자·참여자에게 알림만, 신고·경고는 없다")
    void firstLeftItemsNotifiesOnly() {
        String items = "[{\"name\":\"umbrella\",\"confidence\":0.93},{\"name\":\"cup\",\"confidence\":0.71}]";

        assertThat(post(1, first("e2", ended, "LEFT_ITEMS", items))).hasStatusOk().bodyJson()
                .extractingPath("$.reportId").isNull();

        assertThat(inbox("LEFT_ITEM")).extracting(m -> ((Number) m.get("user_id")).longValue())
                .containsExactly(1L, 2L, 3L);
        assertThat(inbox("LEFT_ITEM").getFirst().get("body")).asString().contains("umbrella·cup").contains("3시간 안에");
        assertThat(reports()).isEmpty();
        assertThat(count("user_warning")).isZero();
        assertThat(jdbc.sql("SELECT confidences FROM tidy_check").query((rs, n) -> List.of((Float[]) rs.getArray(1).getArray()))
                .single()).containsExactly(0.93f, 0.71f);
    }

    @Test
    @DisplayName("다시 확인 요청: 두고 간 물건이 있고 방이 비어 있으면 recheck에 예약 번호와 퇴실 + 3시간을 준다")
    void currentShowsRecheck() {
        assertThat(post(1, first("e3", ended, "LEFT_ITEMS", UMBRELLA))).hasStatusOk();
        assertThat(current(1)).bodyJson().extractingPath("$.recheck").as("다음 팀이 사용 중이면 주지 않는다").isNull();

        roomFree();

        String json = body(current(1));
        assertThat(((Number) JsonPath.read(json, "$.recheck.reservationId")).longValue()).isEqualTo(ended);
        ZonedDateTime endedAt = ZonedDateTime.parse(JsonPath.read(json, "$.lastEnded.endedAt"));
        assertThat(ZonedDateTime.parse(JsonPath.<String>read(json, "$.recheck.at")))
                .isEqualTo(endedAt.plusHours(3));
        assertThat(JsonPath.<String>read(json, "$.recheck.at")).endsWith("+09:00");
    }

    @Test
    @DisplayName("수용 기준: 3시간 뒤 다시 봐도 확신도 0.8 이상 물건이 남으면 core가 바로 대표자에게만 경고한다")
    void recheckLeftWarnsOwner() {
        assertThat(post(1, first("e4", ended, "LEFT_ITEMS", UMBRELLA))).hasStatusOk();
        recheckDue();

        MvcTestResult r = post(1, check("e4-r", ended, "RECHECK", "LEFT_ITEMS",
                "[{\"name\":\"umbrella\",\"confidence\":0.91},{\"name\":\"pen\",\"confidence\":0.42}]"));

        assertThat(r).hasStatusOk();
        long reportId = ((Number) JsonPath.read(body(r), "$.reportId")).longValue();
        assertThat(reports()).singleElement().satisfies(p -> {
            assertThat(((Number) p.get("id")).longValue()).isEqualTo(reportId);
            assertThat(p).containsEntry("source", "CAMERA").containsEntry("category", "MESSY")
                    .containsEntry("verdict", "CONFIRMED");
            assertThat(p.get("judged_by")).as("사람이 판정하지 않음").isNull();
            assertThat(p.get("judged_at")).isNotNull();
            assertThat(p.get("reporter_id")).isNull();
            assertThat(((Number) p.get("target_reservation_id")).longValue()).isEqualTo(ended);
            assertThat((String) p.get("content")).contains("umbrella(0.91)").doesNotContain("pen");
        });
        assertThat(jdbc.sql("SELECT user_id, reason, reservation_id FROM user_warning").query().listOfRows())
                .singleElement().satisfies(w -> {
                    assertThat(((Number) w.get("user_id")).longValue()).isEqualTo(OWNER);
                    assertThat(w).containsEntry("reason", "VIOLATION");
                    assertThat(((Number) w.get("reservation_id")).longValue()).isEqualTo(ended);
                });
        assertThat(inbox("WARNING")).singleElement().satisfies(m -> {
            assertThat(((Number) m.get("user_id")).longValue()).isEqualTo(OWNER);
            assertThat((String) m.get("body")).startsWith("두고 간 물건을 3시간 안에 챙겨 가지 않았어요.");
        });
        assertThat(jdbc.sql("SELECT report_id FROM tidy_check WHERE phase = 'RECHECK'")
                .query((rs, n) -> rs.getLong(1)).single()).isEqualTo(reportId);
        assertThat(current(1)).bodyJson().extractingPath("$.recheck").as("다시 확인은 한 번").isNull();
    }

    @Test
    @DisplayName("다시 봤을 때 남은 물건의 확신도가 모두 0.8 미만이거나 깨끗하면 경고 없이 결과만 남는다")
    void recheckUnsureOrCleanNoWarning() {
        assertThat(post(1, first("e5", ended, "LEFT_ITEMS", UMBRELLA))).hasStatusOk();
        recheckDue();

        assertThat(post(1, check("e5-r", ended, "RECHECK", "LEFT_ITEMS",
                "[{\"name\":\"umbrella\",\"confidence\":0.79}]"))).hasStatusOk()
                .bodyJson().extractingPath("$.reportId").isNull();

        assertThat(reports()).isEmpty();
        assertThat(count("user_warning")).isZero();
        assertThat(inbox("WARNING")).isEmpty();
    }

    @Test
    @DisplayName("다시 확인 거절: 시각 전이면 TIDY_RECHECK_TOO_EARLY, 첫 확인이 깨끗했거나 누가 방에 있으면 NOT_NEEDED")
    void recheckGuards() {
        // 퇴실 1시간 뒤 (3시간 전)
        assertThat(post(1, first("e6", ended, "LEFT_ITEMS", UMBRELLA))).hasStatusOk();
        assertThat(post(1, check("e6-r1", ended, "RECHECK", "CLEAN", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_RECHECK_NOT_NEEDED");   // 다음 팀 사용 중
        roomFree();
        assertThat(post(1, check("e6-r2", ended, "RECHECK", "CLEAN", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_RECHECK_TOO_EARLY");
        // 예전 기한(2시간)은 지났지만 3시간 전이면 아직 이르다
        jdbc.sql("UPDATE reservation SET checked_out_at = now() - interval '150 minutes' WHERE id = :id")
                .param("id", ended).update();
        assertThat(post(1, check("e6-r2b", ended, "RECHECK", "CLEAN", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_RECHECK_TOO_EARLY");

        // 첫 확인이 깨끗했으면 다시 볼 것이 없다
        jdbc.sql("UPDATE tidy_check SET result = 'CLEAN', items = '{}', confidences = '{}'").update();
        recheckDue();
        assertThat(post(1, check("e6-r3", ended, "RECHECK", "CLEAN", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_RECHECK_NOT_NEEDED");
        assertThat(count("tidy_check")).isEqualTo(1);
    }

    @Test
    @DisplayName("기한 전에 본 화면을 기한 뒤에 보내면 TIDY_RECHECK_TOO_EARLY (그 사이 가져갔을 수 있다)")
    void recheckObservedBeforeDue() {
        assertThat(post(1, first("e18", ended, "LEFT_ITEMS", UMBRELLA))).hasStatusOk();
        recheckDue();
        String early = OffsetDateTime.now(ZoneOffset.ofHours(9)).minusHours(2).withNano(0).toString();

        assertThat(post(1, check("e18-r", ended, "RECHECK", "LEFT_ITEMS", UMBRELLA)
                .replaceFirst("\"observedAt\":\"[^\"]+\"", "\"observedAt\":\"" + early + "\"")))
                .hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("TIDY_RECHECK_TOO_EARLY");
        assertThat(count("user_warning")).isZero();
    }

    @Test
    @DisplayName("다음 팀이 입실해 있으면 지금 예약(current)이 다른 예약이어도 다시 보지 않는다")
    void nextTeamInUseWhileBookedShown() {
        // given: 앞 예약 4시간 전 퇴실, 다음 팀(now) 입실 중, 그 뒤 예약이 시간이 돼 BOOKED로 걸림
        assertThat(post(1, first("e19", ended, "LEFT_ITEMS", UMBRELLA))).hasStatusOk();
        jdbc.sql("UPDATE reservation SET checked_out_at = now() - interval '4 hours' WHERE id = :id")
                .param("id", ended).update();

        assertThat(current(1)).bodyJson().extractingPath("$.recheck").isNull();
        assertThat(post(1, check("e19-r", ended, "RECHECK", "LEFT_ITEMS", UMBRELLA))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_RECHECK_NOT_NEEDED");
    }

    @Test
    @DisplayName("물건 이름이 길고 많아도 알림 본문은 300자 안, 기한 문장은 남는다")
    void longItemNamesFitNotification() {
        String items = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> "{\"name\":\"" + String.valueOf((char) ('a' + i)).repeat(50) + "\",\"confidence\":0.9}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        assertThat(post(1, first("e20", ended, "LEFT_ITEMS", items))).hasStatusOk();

        assertThat(inbox("LEFT_ITEM")).hasSize(3).allSatisfy(m -> assertThat((String) m.get("body"))
                .hasSizeLessThanOrEqualTo(300).contains(" 외 ").endsWith("3시간 안에 챙겨 가 주세요."));
    }

    @Test
    @DisplayName("3시간 안에 다음 예약이 입실했다 나가면 앞 예약은 다시 보지 않는다(경고 없음, 409 NOT_LATEST)")
    void nextTeamUsedRoom() {
        assertThat(post(1, first("e7", ended, "LEFT_ITEMS", UMBRELLA))).hasStatusOk();
        jdbc.sql("UPDATE reservation SET checked_out_at = now() - interval '4 hours' WHERE id = :id")
                .param("id", ended).update();
        jdbc.sql("UPDATE reservation SET status = 'CHECKED_OUT', checked_out_at = now() WHERE id = :id")
                .param("id", now).update();

        assertThat(current(1)).bodyJson().extractingPath("$.recheck").isNull();
        assertThat(post(1, check("e7-r", ended, "RECHECK", "LEFT_ITEMS", UMBRELLA))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_CHECK_NOT_LATEST");
        assertThat(count("user_warning")).isZero();
    }

    @Test
    @DisplayName("같은 eventId를 두 번 보내면 한 번만 처리한다. 다시 확인도 같다")
    void duplicateEvent() {
        String json = first("e8", ended, "LEFT_ITEMS", UMBRELLA);
        assertThat(post(1, json)).hasStatusOk();
        assertThat(post(1, json)).hasStatusOk().bodyJson().extractingPath("$.result").isEqualTo("DUPLICATE");
        assertThat(inbox("LEFT_ITEM")).hasSize(3);

        recheckDue();
        String again = check("e8-r", ended, "RECHECK", "LEFT_ITEMS", UMBRELLA);
        Object reportId = JsonPath.read(body(post(1, again)), "$.reportId");
        assertThat(post(1, again)).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.result").isEqualTo("DUPLICATE");
            j.assertThat().extractingPath("$.reportId").isEqualTo(reportId);
        });
        assertThat(count("user_warning")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 eventId를 다른 예약·다른 단계에 쓰면 처음 결과를 돌려주지 않고 409")
    void sameEventOtherReservationOrPhase() {
        assertThat(post(1, first("e9", ended, "LEFT_ITEMS", UMBRELLA))).hasStatusOk();
        recheckDue();
        assertThat(post(1, check("e9", ended, "RECHECK", "CLEAN", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_CHECK_EXISTS");

        jdbc.sql("UPDATE reservation SET status = 'CHECKED_OUT', checked_in_at = now(), checked_out_at = now() "
                + "WHERE id = :id").param("id", now).update();
        assertThat(post(3, first("e9", now, "CLEAN", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_CHECK_EXISTS");
    }

    @Test
    @DisplayName("단계마다 결과는 하나: 다른 eventId로 또 보내면 409 TIDY_CHECK_EXISTS")
    void onePerPhase() {
        assertThat(post(1, first("e10", ended, "LEFT_ITEMS", UMBRELLA))).hasStatusOk();
        assertThat(post(1, first("e11", ended, "CLEAN", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_CHECK_EXISTS");

        recheckDue();
        assertThat(post(1, check("e12", ended, "RECHECK", "CLEAN", "[]"))).hasStatusOk();
        assertThat(post(1, check("e13", ended, "RECHECK", "LEFT_ITEMS", UMBRELLA))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_CHECK_EXISTS");
        assertThat(count("user_warning")).isZero();
    }

    @Test
    @DisplayName("아직 끝나지 않은 예약은 409, 다른 방 예약 번호는 404")
    void notEndedOrOtherRoom() {
        assertThat(post(1, first("e14", now, "CLEAN", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_ENDED");
        assertThat(post(2, first("e15", ended, "CLEAN", "[]"))).hasStatus(404)
                .bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_FOUND");
    }

    @Test
    @DisplayName("지난 예약은 409 TIDY_CHECK_NOT_LATEST: 이 방의 마지막 퇴실 예약만 받는다")
    void onlyLatestEnded() {
        long older = tx.execute(st -> insertWithPeople("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                         checked_in_at, checked_out_at)
                SELECT 1, 1, t - interval '4 hours', t - interval '3 hours', 3, '333333', 'CHECKED_OUT',
                       t - interval '4 hours', t - interval '3 hours'
                FROM (SELECT date_trunc('hour', now()) AS t) x
                RETURNING id
                """, OWNER, 2, 3));

        assertThat(post(1, first("e16", older, "LEFT_ITEMS", UMBRELLA))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_CHECK_NOT_LATEST");
        assertThat(inbox("LEFT_ITEM")).isEmpty();
    }

    @Test
    @DisplayName("방 지금 예약: 시작 시각이 지났는데 아직 입실 전(BOOKED)인 예약도 current로 준다")
    void currentShowsBookedInTime() {
        long booked = tx.execute(st -> insertWithPeople("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status)
                SELECT 2, 1, t, t + interval '2 hours', 3, '444444', 'BOOKED'
                FROM (SELECT date_trunc('hour', now()) AS t) x   -- 2시간: 넣은 뒤 정시가 바뀌어도 아직 진행 중
                RETURNING id
                """, OWNER, 2, 3));

        assertThat(current(2)).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.current.reservationId").isEqualTo((int) booked);
            j.assertThat().extractingPath("$.current.status").isEqualTo("BOOKED");
        });
    }

    @Test
    @DisplayName("형식이 틀리면 400: 예전 NEEDS_CLEANING, 단계 오타, 확신도 없음·범위 밖, 물건 수와 결과가 안 맞음, 오프셋 없는 시각")
    void invalidInput() {
        List<String> bad = List.of(
                first("b1", ended, "DIRTY", "[]"),
                first("b2", ended, "NEEDS_CLEANING", UMBRELLA),
                check("b3", ended, "LATER", "CLEAN", "[]"),
                first("b4", ended, "LEFT_ITEMS", "[]"),
                first("b5", ended, "CLEAN", UMBRELLA),
                first("b6", ended, "LEFT_ITEMS", "[{\"name\":\"umbrella\"}]"),
                first("b7", ended, "LEFT_ITEMS", "[{\"name\":\"umbrella\",\"confidence\":1.2}]"),
                first("b8", ended, "LEFT_ITEMS", "[{\"name\":\"umbrella\",\"confidence\":-0.1}]"),
                first("b9", ended, "LEFT_ITEMS", "[{\"name\":\"\",\"confidence\":0.9}]"),
                first("b10", ended, "LEFT_ITEMS", "[{\"name\":\"" + "x".repeat(51) + "\",\"confidence\":0.9}]"),
                first("b11", ended, "LEFT_ITEMS", "[\"umbrella\"]"),
                first("b12", ended, "CLEAN", "null"),
                first("b13", ended, "CLEAN", "[]").replace("+09:00", ""),
                first("", ended, "CLEAN", "[]"),
                """
                        {"eventId":"b14","deviceId":"jetson-b","result":"CLEAN","items":[],"observedAt":"2026-11-06T15:01:00+09:00"}""");
        for (String json : bad) {
            assertThat(post(1, json)).as(json).hasStatus(400);
        }
        assertThat(count("tidy_check")).isZero();
    }

    @Test
    @DisplayName("phase를 빼면 FIRST로 받는다")
    void phaseDefaultsToFirst() {
        String json = first("e17", ended, "CLEAN", "[]").replace("\"phase\":\"FIRST\",", "");

        assertThat(post(1, json)).hasStatusOk();
        assertThat(jdbc.sql("SELECT phase FROM tidy_check").query((rs, n) -> rs.getString(1)).single()).isEqualTo("FIRST");
    }

    MvcTestResult list(long userId, String days) {
        var req = mvc.get().uri("/api/tidy-checks").header("X-User-Id", String.valueOf(userId));
        return days == null ? req.exchange() : req.param("days", days).exchange();
    }

    @Test
    @DisplayName("관리자 정리 확인 목록: 받은 결과를 최근 순으로, 단계·물건별 확신도·경고 신고 번호와 함께 준다")
    void adminList() {
        // given: 퇴실 직후 우산을 두고 갔고, 3시간 뒤에도 남아 경고
        assertThat(post(1, first("e-a", ended, "LEFT_ITEMS", UMBRELLA))).hasStatusOk();
        recheckDue();
        long reportId = ((Number) JsonPath.read(body(post(1, check("e-b", ended, "RECHECK", "LEFT_ITEMS",
                UMBRELLA))), "$.reportId")).longValue();

        // when
        MvcTestResult r = list(ADMIN, null);

        // then
        assertThat(r).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.items.length()").isEqualTo(2);
            j.assertThat().extractingPath("$.items[0].phase").isEqualTo("RECHECK");
            j.assertThat().extractingPath("$.items[0].reportId").isEqualTo((int) reportId);
            j.assertThat().extractingPath("$.items[1].reservationId").isEqualTo((int) ended);
            j.assertThat().extractingPath("$.items[1].roomId").isEqualTo(1);
            j.assertThat().extractingPath("$.items[1].roomName").isNotNull();
            j.assertThat().extractingPath("$.items[1].endedAt").asString().endsWith("+09:00");
            j.assertThat().extractingPath("$.items[1].phase").isEqualTo("FIRST");
            j.assertThat().extractingPath("$.items[1].result").isEqualTo("LEFT_ITEMS");
            j.assertThat().extractingPath("$.items[1].items[0].name").isEqualTo("umbrella");
            j.assertThat().extractingPath("$.items[1].items[0].confidence").isEqualTo(0.93);
            j.assertThat().extractingPath("$.items[1].leftItems").isEqualTo(List.of());
            j.assertThat().extractingPath("$.items[1].reportId").isNull();
            j.assertThat().extractingPath("$.items[1].observedAt").isEqualTo("2026-11-06T15:01:00+09:00");
        });
    }

    @Test
    @DisplayName("관리자 정리 확인 목록: 11/10 전 기록(확신도 없음, 귀중품 칸)도 그대로 보인다")
    void adminListLegacyRow() {
        jdbc.sql("""
                INSERT INTO tidy_check (event_id, reservation_id, room_id, device_id, phase, result, items, left_items,
                                        observed_at, received_at)
                VALUES ('old', :rid, 1, 'jetson-b', 'FIRST', 'CLEAN', ARRAY['backpack'], ARRAY['BAG'], now(), now())
                """).param("rid", ended).update();

        assertThat(list(ADMIN, null)).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.items[0].items[0].name").isEqualTo("backpack");
            j.assertThat().extractingPath("$.items[0].items[0].confidence").isNull();
            j.assertThat().extractingPath("$.items[0].leftItems").isEqualTo(List.of("BAG"));
        });
    }

    @Test
    @DisplayName("관리자 정리 확인 목록: days일 전보다 먼저 받은 결과는 빠진다")
    void adminListDays() {
        assertThat(post(1, first("e-old", ended, "CLEAN", "[]"))).hasStatusOk();
        jdbc.sql("UPDATE tidy_check SET received_at = now() - interval '8 days'").update();

        assertThat(list(ADMIN, null)).hasStatusOk().bodyJson().extractingPath("$.items.length()").isEqualTo(0);
        assertThat(list(ADMIN, "9")).hasStatusOk().bodyJson().extractingPath("$.items.length()").isEqualTo(1);
    }

    @Test
    @DisplayName("관리자 정리 확인 목록: 학생은 403 ADMIN_ONLY, days가 1–92 밖이면 400")
    void adminListGuards() {
        assertThat(list(OWNER, null)).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("ADMIN_ONLY");
        assertThat(list(ADMIN, "0")).hasStatus(400);
        assertThat(list(ADMIN, "93")).hasStatus(400);
    }
}
