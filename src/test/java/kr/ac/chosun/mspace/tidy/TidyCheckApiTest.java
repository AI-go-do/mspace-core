package kr.ac.chosun.mspace.tidy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
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
 * 퇴실 뒤 정리 확인 (FR-DEV-105, 11/6 지영 "자동신고+관리자 판정", 두고 간 물건은 가방·휴대폰·무선 이어폰만).
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

    String check(String eventId, long reservationId, String result, String items, String leftItems) {
        return """
                {"eventId":"%s","reservationId":%d,"deviceId":"jetson-b","result":"%s",
                 "items":%s,"leftItems":%s,"observedAt":"2026-11-06T15:01:00+09:00"}"""
                .formatted(eventId, reservationId, result, items, leftItems);
    }

    static String body(MvcTestResult r) {
        try {
            return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    List<Map<String, Object>> reports() {
        return jdbc.sql("""
                SELECT id, source, category, reporter_id, reporter_reservation_id, target_reservation_id, content
                FROM report ORDER BY id
                """).query().listOfRows();
    }

    List<Map<String, Object>> inbox(String kind) {
        return jdbc.sql("SELECT user_id, title, body FROM notification WHERE kind = :k ORDER BY user_id")
                .param("k", kind).query().listOfRows();
    }

    MvcTestResult judge(long reportId, String verdict) {
        return mvc.post().uri("/api/reports/{id}/judge", reportId).header("X-User-Id", String.valueOf(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"" + verdict + "\"}").exchange();
    }

    @Test
    @DisplayName("방 지금 예약: 사용 중인 예약과 방금 끝난 예약 번호를 준다")
    void currentShowsInUseAndLastEnded() {
        MvcTestResult r = mvc.get().uri("/api/rooms/1/current").header("Authorization", TOKEN).exchange();

        assertThat(r).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.roomId").isEqualTo(1);
            j.assertThat().extractingPath("$.current.reservationId").isEqualTo((int) now);
            j.assertThat().extractingPath("$.current.status").isEqualTo("IN_USE");
            j.assertThat().extractingPath("$.lastEnded.reservationId").isEqualTo((int) ended);
            j.assertThat().extractingPath("$.lastEnded.status").isEqualTo("CHECKED_OUT");
        });
        assertThat(body(r)).contains("+09:00");
    }

    @Test
    @DisplayName("방 지금 예약: 빈 방이면 current·lastEnded 둘 다 null")
    void currentEmptyRoom() {
        assertThat(mvc.get().uri("/api/rooms/2/current").header("Authorization", TOKEN)).hasStatusOk()
                .bodyJson().satisfies(j -> {
                    j.assertThat().extractingPath("$.current").isNull();
                    j.assertThat().extractingPath("$.lastEnded").isNull();
                });
    }

    @Test
    @DisplayName("장치 토큰이 없거나 틀리면 401, 없는 방은 404")
    void tokenAndRoom() {
        assertThat(mvc.get().uri("/api/rooms/1/current")).hasStatus(401);
        assertThat(mvc.get().uri("/api/rooms/1/current").header("Authorization", "Bearer wrong")).hasStatus(401);
        assertThat(mvc.post().uri("/api/rooms/1/tidy-checks").contentType(MediaType.APPLICATION_JSON)
                .content(check("e0", ended, "CLEAN", "[]", "[]"))).hasStatus(401);
        assertThat(mvc.get().uri("/api/rooms/99/current").header("Authorization", TOKEN)).hasStatus(404)
                .bodyJson().extractingPath("$.code").isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    @DisplayName("수용 기준: 깨끗하면 결과만 남고 신고·알림은 없다")
    void cleanStoresOnly() {
        assertThat(post(1, check("e1", ended, "CLEAN", "[]", "[]"))).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.result").isEqualTo("STORED");
            j.assertThat().extractingPath("$.reportId").isNull();
        });

        assertThat(reports()).isEmpty();
        assertThat(inbox("LEFT_ITEM")).isEmpty();
        assertThat(jdbc.sql("SELECT result FROM tidy_check").query((rs, n) -> rs.getString(1)).list())
                .containsExactly("CLEAN");
    }

    @Test
    @DisplayName("수용 기준: 치울 것이 있으면 신고자 없는 카메라 어질러짐 신고가 자동으로 생긴다")
    void needsCleaningFilesCameraReport() {
        MvcTestResult r = post(1, check("e2", ended, "NEEDS_CLEANING", "[\"cup\",\"bottle\"]", "[]"));

        assertThat(r).hasStatusOk().bodyJson().extractingPath("$.result").isEqualTo("STORED");
        long reportId = ((Number) JsonPath.read(body(r), "$.reportId")).longValue();
        assertThat(reports()).singleElement().satisfies(p -> {
            assertThat(((Number) p.get("id")).longValue()).isEqualTo(reportId);
            assertThat(p.get("source")).isEqualTo("CAMERA");
            assertThat(p.get("category")).isEqualTo("MESSY");
            assertThat(p.get("reporter_id")).isNull();
            assertThat(p.get("reporter_reservation_id")).isNull();
            assertThat(((Number) p.get("target_reservation_id")).longValue()).isEqualTo(ended);
            assertThat((String) p.get("content")).contains("cup, bottle");
        });
        assertThat(mvc.get().uri("/api/reports").header("X-User-Id", String.valueOf(ADMIN)))
                .hasStatusOk().bodyJson().satisfies(j -> {
                    j.assertThat().extractingPath("$[0].source").isEqualTo("CAMERA");
                    j.assertThat().extractingPath("$[0].reporterReservationId").isNull();
                });
    }

    @Test
    @DisplayName("수용 기준: 카메라 신고를 사실로 판정하면 그 예약 대표자만 경고, 신고자 알림은 없다")
    void confirmedCameraReportWarnsThatOwner() {
        long reportId = ((Number) JsonPath.read(body(post(1, check("e3", ended, "NEEDS_CLEANING", "[\"cup\"]", "[]"))),
                "$.reportId")).longValue();

        assertThat(judge(reportId, "CONFIRMED")).hasStatusOk();

        assertThat(jdbc.sql("SELECT user_id, reason, reservation_id FROM user_warning").query().listOfRows())
                .singleElement().satisfies(w -> {
                    assertThat(((Number) w.get("user_id")).longValue()).isEqualTo(OWNER);
                    assertThat(w.get("reason")).isEqualTo("VIOLATION");
                    assertThat(((Number) w.get("reservation_id")).longValue()).isEqualTo(ended);
                });
        assertThat(inbox("REPORT_RESULT")).isEmpty();
    }

    @Test
    @DisplayName("카메라 신고를 허위로 판정해도 경고할 신고자가 없으니 아무도 경고받지 않는다")
    void falseCameraReportWarnsNobody() {
        long reportId = ((Number) JsonPath.read(body(post(1, check("e4", ended, "NEEDS_CLEANING", "[\"cup\"]", "[]"))),
                "$.reportId")).longValue();

        assertThat(judge(reportId, "FALSE")).hasStatusOk();
        assertThat(judge(reportId, "UNVERIFIED")).hasStatus(409);

        assertThat(jdbc.sql("SELECT count(*) FROM user_warning").query((rs, n) -> rs.getLong(1)).single()).isZero();
    }

    @Test
    @DisplayName("수용 기준: 가방·휴대폰·노트북을 두고 가면 그 예약 대표자·참여자 모두에게 알림")
    void leftItemsNotifyMembers() {
        assertThat(post(1, check("e5", ended, "CLEAN", "[]", "[\"BAG\",\"PHONE\",\"LAPTOP\"]"))).hasStatusOk();

        assertThat(inbox("LEFT_ITEM")).extracting(m -> ((Number) m.get("user_id")).longValue())
                .containsExactly(1L, 2L, 3L);
        assertThat(inbox("LEFT_ITEM").getFirst().get("body")).asString().contains("가방·휴대폰·노트북");
        assertThat(reports()).isEmpty();
    }

    @Test
    @DisplayName("같은 eventId를 두 번 보내면 한 번만 처리하고 처음 reportId를 돌려준다")
    void duplicateEvent() {
        String json = check("e6", ended, "NEEDS_CLEANING", "[\"cup\"]", "[\"PHONE\"]");
        Object first = JsonPath.read(body(post(1, json)), "$.reportId");

        assertThat(post(1, json)).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.result").isEqualTo("DUPLICATE");
            j.assertThat().extractingPath("$.reportId").isEqualTo(first);
        });
        assertThat(reports()).hasSize(1);
        assertThat(inbox("LEFT_ITEM")).hasSize(3);
    }

    @Test
    @DisplayName("같은 eventId를 다른 예약에 쓰면 처음 결과를 돌려주지 않고 409")
    void sameEventOtherReservation() {
        assertThat(post(1, check("e11", ended, "CLEAN", "[]", "[]"))).hasStatusOk();
        jdbc.sql("UPDATE reservation SET status = 'CHECKED_OUT', checked_out_at = now() WHERE id = :id")
                .param("id", now).update();

        assertThat(post(1, check("e11", now, "CLEAN", "[]", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_CHECK_EXISTS");
    }

    @Test
    @DisplayName("한 예약에 결과는 하나: 다른 eventId로 또 보내면 409 TIDY_CHECK_EXISTS")
    void onePerReservation() {
        assertThat(post(1, check("e7", ended, "CLEAN", "[]", "[]"))).hasStatusOk();

        assertThat(post(1, check("e8", ended, "NEEDS_CLEANING", "[\"cup\"]", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_CHECK_EXISTS");
        assertThat(reports()).isEmpty();
    }

    @Test
    @DisplayName("아직 끝나지 않은 예약은 409, 다른 방 예약 번호는 404")
    void notEndedOrOtherRoom() {
        assertThat(post(1, check("e9", now, "CLEAN", "[]", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_ENDED");
        assertThat(post(2, check("e10", ended, "CLEAN", "[]", "[]"))).hasStatus(404)
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

        assertThat(post(1, check("e12", older, "NEEDS_CLEANING", "[\"cup\"]", "[]"))).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("TIDY_CHECK_NOT_LATEST");
        assertThat(reports()).isEmpty();
    }

    @Test
    @DisplayName("방 지금 예약: 시작 시각이 지났는데 아직 입실 전(BOOKED)인 예약도 current로 준다")
    void currentShowsBookedInTime() {
        long booked = tx.execute(st -> insertWithPeople("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status)
                SELECT 2, 1, t, t + interval '1 hour', 3, '444444', 'BOOKED'
                FROM (SELECT date_trunc('hour', now()) AS t) x
                RETURNING id
                """, OWNER, 2, 3));

        assertThat(mvc.get().uri("/api/rooms/2/current").header("Authorization", TOKEN)).hasStatusOk()
                .bodyJson().satisfies(j -> {
                    j.assertThat().extractingPath("$.current.reservationId").isEqualTo((int) booked);
                    j.assertThat().extractingPath("$.current.status").isEqualTo("BOOKED");
                });
    }

    @Test
    @DisplayName("형식이 틀리면 400: 결과·물건 종류 오타, 오프셋 없는 시각, items 없음, 너무 긴 이름")
    void invalidInput() {
        List<String> bad = List.of(
                check("b1", ended, "DIRTY", "[]", "[]"),
                check("b2", ended, "CLEAN", "[]", "[\"WALLET\"]"),
                check("b3", ended, "CLEAN", "null", "[]"),
                check("b4", ended, "CLEAN", "[\"" + "x".repeat(51) + "\"]", "[]"),
                check("b5", ended, "CLEAN", "[\"\"]", "[]"),
                check("b6", ended, "CLEAN", "[]", "[]").replace("+09:00", ""),
                check("", ended, "CLEAN", "[]", "[]"),
                """
                        {"eventId":"b7","deviceId":"jetson-b","result":"CLEAN","items":[],"observedAt":"2026-11-06T15:01:00+09:00"}""");
        for (String json : bad) {
            assertThat(post(1, json)).as(json).hasStatus(400);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM tidy_check").query((rs, n) -> rs.getLong(1)).single()).isZero();
    }
}
