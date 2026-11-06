package kr.ac.chosun.mspace.reservation;

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

/** 관리자 강제 퇴실 (FR-RPT-104 퇴출). 회원 1(대표)·2·3이 1번 방 사용 중, 회원 4는 관리자 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ForceCheckoutApiTest {

    static final long ADMIN = 4;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    long inUse;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE notification, reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 4; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "f" + i + "@chosun.ac.kr").param("s", "202400" + (20 + i)).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
        inUse = reservation("IN_USE", 0);
    }

    /** 1번 회원이 대표, 1·2·3 참여. hoursFromNow 뒤 시작 */
    long reservation(String status, int hoursFromNow) {
        return tx.execute(s -> {
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                     checked_in_at)
                            SELECT 1, 1, t, t + interval '1 hour', 3, '123456', :status,
                                   CASE WHEN :status = 'IN_USE' THEN t END
                            FROM (SELECT date_trunc('hour', now()) + make_interval(hours => :h) AS t) x
                            RETURNING id
                            """).param("status", status).param("h", hoursFromNow).query(Long.class).single();
            for (long uid = 1; uid <= 3; uid++) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                                """).param("rid", id).param("uid", uid).param("owner", uid == 1).update();
            }
            return id;
        });
    }

    MvcTestResult force(long userId, long reservationId, String body) {
        var req = mvc.post().uri("/api/reservations/{id}/force-checkout", reservationId)
                .header("X-User-Id", String.valueOf(userId));
        return body == null ? req.exchange() : req.contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    String status(long id) {
        return jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    @DisplayName("관리자가 사용 중인 예약을 끝내면 200 CHECKED_OUT, 누가·왜가 남고 세 명 모두 알림을 받는다")
    void adminForcesCheckout() {
        MvcTestResult r = force(ADMIN, inUse, "{\"reason\":\"소음 신고 확인\"}");

        assertThat(r).hasStatus(200);
        assertThat(r).bodyJson().extractingPath("$.status").isEqualTo("CHECKED_OUT");
        var row = jdbc.sql("SELECT forced_by, forced_reason, checked_out_at IS NOT NULL AS out FROM reservation WHERE id = :id")
                .param("id", inUse).query().singleRow();
        assertThat(row.get("forced_by")).isEqualTo(ADMIN);
        assertThat(row.get("forced_reason")).isEqualTo("소음 신고 확인");
        assertThat(row.get("out")).isEqualTo(true);
        List<Long> notified = jdbc.sql("SELECT user_id FROM notification WHERE kind = 'FORCED_CHECKOUT' ORDER BY user_id")
                .query(Long.class).list();
        assertThat(notified).containsExactly(1L, 2L, 3L);
        assertThat(jdbc.sql("SELECT body FROM notification LIMIT 1").query(String.class).single())
                .contains("관리자가 퇴실 처리").contains("사유: 소음 신고 확인");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation_participant WHERE active").query(Long.class).single())
                .isZero();
    }

    @Test
    @DisplayName("사유 없이도 된다 (본문 없음)")
    void withoutReason() {
        assertThat(force(ADMIN, inUse, null)).hasStatus(200);
        assertThat(jdbc.sql("SELECT forced_reason FROM reservation WHERE id = :id").param("id", inUse)
                .query(String.class).optional()).isEmpty();
    }

    @Test
    @DisplayName("학생은 남의 예약도 자기 예약도 강제 퇴실 403 ADMIN_ONLY, 그대로 사용 중")
    void studentForbidden() {
        MvcTestResult owner = force(1, inUse, null);
        MvcTestResult other = force(2, inUse, null);

        assertThat(owner).hasStatus(403);
        assertThat(owner).bodyJson().extractingPath("$.code").isEqualTo("ADMIN_ONLY");
        assertThat(other).hasStatus(403);
        assertThat(status(inUse)).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("없는 예약 404, 입실 전·이미 끝난 예약 409 RESERVATION_NOT_IN_USE")
    void notInUse() {
        force(ADMIN, inUse, null);
        long booked = reservation("BOOKED", 3);

        assertThat(force(ADMIN, 999, null)).hasStatus(404);
        MvcTestResult again = force(ADMIN, inUse, null);
        assertThat(again).hasStatus(409);
        assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_IN_USE");
        assertThat(force(ADMIN, booked, null)).hasStatus(409);
        assertThat(status(booked)).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("사유가 200자를 넘으면 400, 예약은 그대로")
    void reasonTooLong() {
        MvcTestResult r = force(ADMIN, inUse, "{\"reason\":\"" + "가".repeat(201) + "\"}");

        assertThat(r).hasStatus(400);
        assertThat(status(inUse)).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("관리자 두 명이 동시에 10번 눌러도 한 번만 되고 알림도 세 개뿐")
    void concurrent() throws Exception {
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return force(ADMIN, inUse, null).getResponse().getStatus();
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) {
                ok++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE kind = 'FORCED_CHECKOUT'").query(Long.class).single())
                .isEqualTo(3);
    }
}
