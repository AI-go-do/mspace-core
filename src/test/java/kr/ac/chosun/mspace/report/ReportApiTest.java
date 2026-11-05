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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 신고 (FR-RPT-101). 옆 방은 V8(1–2, 2–3, 3–4, 4–5).
 * 1번 방: 회원 1(대표)·2·3이 사용 중. 2번 방: 회원 4(대표)·5·6이 사용 중. 회원 7·8·9는 3번 방을 예약만 해 둠(BOOKED).
 * 회원 10은 예약이 없다.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    long room1;
    long room2;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE report, reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 10; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "202300" + (10 + i)).update();
        }
        room1 = reservation(1, "IN_USE", 1, 2, 3);
        room2 = reservation(2, "IN_USE", 4, 5, 6);
        reservation(3, "BOOKED", 7, 8, 9);
    }

    /** 지금 시각을 끼는 1시간 예약과 이용자를 한 트랜잭션으로 넣는다. 첫 번째가 대표자 */
    long reservation(int roomId, String status, long... people) {
        return tx.execute(s -> {
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                     checked_in_at)
                            SELECT :room, :owner, t, t + interval '1 hour', :n, '123456', :status,
                                   CASE WHEN :status = 'IN_USE' THEN t END
                            FROM (SELECT date_trunc('hour', now()) AS t) x
                            RETURNING id
                            """)
                    .param("room", roomId).param("owner", people[0]).param("n", people.length)
                    .param("status", status)
                    .query(Long.class).single();
            for (long uid : people) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                                """)
                        .param("rid", id).param("uid", uid).param("owner", uid == people[0]).update();
            }
            return id;
        });
    }

    MvcTestResult report(long userId, String body) {
        return mvc.post().uri("/api/reports").header("X-User-Id", String.valueOf(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    MvcTestResult report(long userId, int roomId, String category) {
        return report(userId, """
                {"roomId":%d,"category":"%s","content":"의자 다리가 부러졌어요"}""".formatted(roomId, category));
    }

    long reportCount() {
        return jdbc.sql("SELECT count(*) FROM report").query(Long.class).single();
    }

    @Test
    @DisplayName("사용 중인 방의 대표자가 그 방을 신고하면 201, 판정 대기(OPEN)로 저장되고 근거 예약이 남는다")
    void ownerReportsOwnRoom() {
        MvcTestResult r = report(1, 1, "BROKEN");

        assertThat(r).hasStatus(201);
        assertThat(r).bodyJson().extractingPath("$.roomId").isEqualTo(1);
        assertThat(r).bodyJson().extractingPath("$.category").isEqualTo("BROKEN");
        assertThat(r).bodyJson().extractingPath("$.verdict").isEqualTo("OPEN");
        assertThat(r).bodyJson().extractingPath("$.createdAt").asString().endsWith("+09:00");
        var row = jdbc.sql("SELECT reporter_id, reporter_reservation_id, content FROM report").query().singleRow();
        assertThat(row.get("reporter_id")).isEqualTo(1L);
        assertThat(row.get("reporter_reservation_id")).isEqualTo(room1);
        assertThat(row.get("content")).isEqualTo("의자 다리가 부러졌어요");
    }

    @Test
    @DisplayName("참여자도 신고할 수 있고, 옆 방(1번 → 2번)도 신고할 수 있다")
    void participantReportsAdjacentRoom() {
        assertThat(report(2, 2, "WINDOW_OPEN")).hasStatus(201);
        assertThat(report(5, 1, "MESSY")).hasStatus(201);

        assertThat(jdbc.sql("SELECT reporter_reservation_id FROM report ORDER BY id").query(Long.class).list())
                .containsExactly(room1, room2);
    }

    @Test
    @DisplayName("옆 방이 아닌 방(1번 방 이용자가 3번 방)은 403 REPORT_NOT_ALLOWED")
    void farRoomForbidden() {
        MvcTestResult r = report(1, 3, "BROKEN");

        assertThat(r).hasStatus(403);
        assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("REPORT_NOT_ALLOWED");
        assertThat(reportCount()).isZero();
    }

    @Test
    @DisplayName("없는 방(0번·99번)은 권한 403이 아니라 404 ROOM_NOT_FOUND")
    void unknownRoomNotFound() {
        for (int roomId : new int[] {0, 99}) {
            MvcTestResult r = report(1, roomId, "BROKEN");

            assertThat(r).hasStatus(404);
            assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("ROOM_NOT_FOUND");
        }
        assertThat(reportCount()).isZero();
    }

    @Test
    @DisplayName("예약만 하고 입실 전(BOOKED)이거나 예약이 없는 회원은 403")
    void notInUseForbidden() {
        assertThat(report(7, 3, "BROKEN")).hasStatus(403);
        assertThat(report(7, 2, "BROKEN")).hasStatus(403);
        assertThat(report(10, 1, "BROKEN")).hasStatus(403);
        assertThat(reportCount()).isZero();
    }

    @Test
    @DisplayName("퇴실한 뒤에는 신고할 수 없다")
    void checkedOutForbidden() {
        jdbc.sql("UPDATE reservation SET status = 'CHECKED_OUT', checked_out_at = now() WHERE id = :id")
                .param("id", room1).update();

        assertThat(report(1, 1, "MESSY")).hasStatus(403);
    }

    @Test
    @DisplayName("소음은 \"정말 소란인가요?\" 체크가 없으면 400 REPORT_NOISE_NOT_CONFIRMED, 체크하면 201")
    void noiseNeedsConfirmation() {
        MvcTestResult missing = report(1, 2, "NOISE");
        MvcTestResult unchecked = report(1, """
                {"roomId":2,"category":"NOISE","noiseConfirmed":false}""");
        MvcTestResult checked = report(1, """
                {"roomId":2,"category":"NOISE","noiseConfirmed":true}""");

        assertThat(missing).hasStatus(400);
        assertThat(missing).bodyJson().extractingPath("$.code").isEqualTo("REPORT_NOISE_NOT_CONFIRMED");
        assertThat(unchecked).hasStatus(400);
        assertThat(checked).hasStatus(201);
        assertThat(jdbc.sql("SELECT content FROM report").query(String.class).optional()).isEmpty();
        assertThat(reportCount()).isOne();
    }

    @Test
    @DisplayName("소음 확인 검사는 권한 검사보다 먼저라, 권한 없는 사람도 체크를 빠뜨리면 400을 먼저 본다")
    void noiseCheckedBeforePermission() {
        assertThat(report(10, 1, "NOISE")).hasStatus(400);
    }

    @Test
    @DisplayName("없는 종류, 빠진 종류·방은 400 INVALID_REQUEST")
    void invalidInput() {
        MvcTestResult unknown = report(1, 1, "SMELL");
        MvcTestResult noCategory = report(1, """
                {"roomId":1}""");
        MvcTestResult noRoom = report(1, """
                {"category":"BROKEN"}""");

        for (MvcTestResult r : new MvcTestResult[]{unknown, noCategory, noRoom}) {
            assertThat(r).hasStatus(400);
            assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        }
        assertThat(reportCount()).isZero();
    }

    @Test
    @DisplayName("메모는 500자까지, 넘으면 400. 공백뿐이면 비워서 저장")
    void contentLength() {
        String ok = "가".repeat(500);
        String tooLong = "가".repeat(501);

        assertThat(report(1, """
                {"roomId":1,"category":"MESSY","content":"%s"}""".formatted(ok))).hasStatus(201);
        assertThat(report(1, """
                {"roomId":1,"category":"MESSY","content":"%s"}""".formatted(tooLong))).hasStatus(400);
        assertThat(report(1, """
                {"roomId":1,"category":"MESSY","content":"   "}""")).hasStatus(201);
        assertThat(jdbc.sql("SELECT content IS NULL FROM report ORDER BY id").query(Boolean.class).list())
                .containsExactly(false, true);
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void unauthorized() {
        MvcTestResult r = mvc.post().uri("/api/reports").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"category":"BROKEN"}""").exchange();

        assertThat(r).hasStatus(401);
    }

    @Test
    @DisplayName("옆 방은 1–2, 2–3, 3–4, 4–5 양방향 8줄 (5번과 1번은 옆 방 아님)")
    void adjacencySeed() {
        assertThat(jdbc.sql("SELECT room_id || '-' || adjacent_room_id FROM room_adjacency ORDER BY 1")
                .query(String.class).list())
                .containsExactly("1-2", "2-1", "2-3", "3-2", "3-4", "4-3", "4-5", "5-4");
    }
}
