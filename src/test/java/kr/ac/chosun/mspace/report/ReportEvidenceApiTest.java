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

/**
 * 신고 증거 사진 (GET /api/reports/{id}/evidence). 1번 방을 회원 1·2·3이 먼저 쓰고 퇴실(previous),
 * 이어서 회원 4·5·6이 입실해 회원 4가 어질러짐 신고. 회원 7은 관리자
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportEvidenceApiTest {

    static final long ADMIN = 7;
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    long previous;
    long mine;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE reservation_photo, report, reservation, reservation_participant, users RESTART IDENTITY CASCADE")
                .update();
        for (int i = 1; i <= 7; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "v" + i + "@chosun.ac.kr").param("s", "202400" + (50 + i)).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
        previous = reservation(1, "CHECKED_OUT", -2);
        mine = reservation(4, "IN_USE", 0);
    }

    long reservation(long firstUser, String status, int hours) {
        return tx.execute(s -> {
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                     checked_in_at, checked_out_at)
                            SELECT 1, :owner, t, t + interval '1 hour', 3, '123456', :status, t,
                                   CASE WHEN :status = 'CHECKED_OUT' THEN t + interval '50 minutes' END
                            FROM (SELECT date_trunc('hour', now()) + make_interval(hours => :h) AS t) x
                            RETURNING id
                            """).param("owner", firstUser).param("status", status).param("h", hours)
                    .query(Long.class).single();
            for (long u = firstUser; u < firstUser + 3; u++) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                                """).param("rid", id).param("uid", u).param("owner", u == firstUser).update();
            }
            return id;
        });
    }

    long report() {
        return jdbc.sql("""
                        INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category)
                        VALUES (1, 4, :rid, 'MESSY') RETURNING id
                        """).param("rid", mine).query(Long.class).single();
    }

    void photo(long reservationId, String kind, long by) {
        jdbc.sql("""
                        INSERT INTO reservation_photo (reservation_id, kind, uploaded_by, content_type, size_bytes, data)
                        VALUES (:r, :k, :u, 'image/png', :n, :d)
                        """).param("r", reservationId).param("k", kind).param("u", by).param("n", PNG.length)
                .param("d", PNG).update();
    }

    MvcTestResult evidence(long userId, long reportId) {
        return mvc.get().uri("/api/reports/{id}/evidence", reportId).header("X-User-Id", String.valueOf(userId))
                .exchange();
    }

    @Test
    @DisplayName("직전 이용자의 퇴실 사진과 신고자의 입실 사진이 있으면 주소를 주고, 관리자는 그 주소로 사진을 연다")
    void bothPhotos() {
        photo(previous, "CHECK_OUT", 1);
        photo(mine, "CHECK_IN", 4);
        long id = report();

        MvcTestResult r = evidence(ADMIN, id);

        assertThat(r).hasStatus(200);
        var json = assertThat(r).bodyJson();
        json.extractingPath("$.previous.reservationId").isEqualTo((int) previous);
        json.extractingPath("$.previous.checkedOutAt").asString().endsWith("+09:00");
        json.extractingPath("$.previous.photo.exists").isEqualTo(true);
        json.extractingPath("$.previous.photo.url").isEqualTo("/api/reservations/" + previous + "/photos/CHECK_OUT");
        json.extractingPath("$.reporter.reservationId").isEqualTo((int) mine);
        json.extractingPath("$.reporter.photo.kind").isEqualTo("CHECK_IN");
        json.extractingPath("$.reporter.photo.exists").isEqualTo(true);
        json.extractingPath("$.note").isNull();
        MvcTestResult image = mvc.get().uri("/api/reservations/" + previous + "/photos/CHECK_OUT")
                .header("X-User-Id", String.valueOf(ADMIN)).exchange();
        assertThat(image).hasStatus(200);
        assertThat(image).hasContentType("image/png");
    }

    @Test
    @DisplayName("사진이 없으면 exists false, 주소 없음, note에 \"올리지 않았어요\"")
    void noPhotos() {
        var json = assertThat(evidence(ADMIN, report())).bodyJson();

        json.extractingPath("$.previous.reservationId").isEqualTo((int) previous);
        json.extractingPath("$.previous.photo.exists").isEqualTo(false);
        json.extractingPath("$.previous.photo.url").isNull();
        json.extractingPath("$.reporter.photo.exists").isEqualTo(false);
        json.extractingPath("$.note").asString().contains("퇴실 사진을 올리지 않았어요").contains("입실 사진을 올리지 않았어요");
    }

    @Test
    @DisplayName("신고 전에 이 방을 쓴 예약이 없으면 previous null, note에 이유")
    void noPrevious() {
        jdbc.sql("UPDATE reservation SET room_id = 2 WHERE id = :id").param("id", previous).update();   // 다른 방

        var json = assertThat(evidence(ADMIN, report())).bodyJson();

        json.extractingPath("$.previous").isNull();
        json.extractingPath("$.note").asString().contains("이 방을 쓴 예약이 없어요");
    }

    @Test
    @DisplayName("학생은 403, 없는 신고는 404")
    void forbiddenAndMissing() {
        long id = report();

        assertThat(evidence(4, id)).hasStatus(403);
        assertThat(evidence(ADMIN, 999)).hasStatus(404);
    }
}
