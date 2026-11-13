package kr.ac.chosun.mspace.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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

/**
 * 관리자 테스트용 입실 확인 (11/13 지영). QR 입실과 같은 처리라 IN_USE가 되고 그 뒤 퇴실도 된다.
 * 예약은 2026-11-03(화) 10:30–11:30, 1번 방, 대표자 1번. 관리자는 6번
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.admin-check-in=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KeypadAndCheckoutApiTest.MovableClock.class})
class AdminCheckInApiTest {

    static final int ADMIN = 6;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KeypadAndCheckoutApiTest.SettableClock clock;

    void now(String hhmm) {
        clock.now = OffsetDateTime.parse("2026-11-03T" + hhmm + ":00+09:00").toInstant();
    }

    @BeforeEach
    void reservation() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, access_event RESTART IDENTITY CASCADE").update();
        jdbc.sql("UPDATE keypad_lock SET failed_count = 0, locked_until = NULL").update();
        for (int i = 1; i <= 6; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
        now("10:10");
        assertThat(mvc.post().uri("/api/reservations").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2026-11-03T10:30:00+09:00","endAt":"2026-11-03T11:30:00+09:00",
                         "participantStudentIds":["20230002","20230003"]}""")
                .exchange()).hasStatus(201);
    }

    MvcTestResult checkIn(int userId, long reservationId) {
        return mvc.post().uri("/api/admin/reservations/" + reservationId + "/check-in")
                .header("X-User-Id", String.valueOf(userId)).exchange();
    }

    String status() {
        return jdbc.sql("SELECT status FROM reservation WHERE id = 1").query(String.class).single();
    }

    int failed() {
        return jdbc.sql("SELECT failed_count FROM keypad_lock WHERE room_id = 1").query(Integer.class).single();
    }

    @Test
    @DisplayName("관리자가 누르면 QR처럼 입실(IN_USE)되고, 기록은 admin-button으로 남고, 대표자가 퇴실할 수 있다")
    void checkInLikeQr() {
        now("10:32");

        MvcTestResult r = checkIn(ADMIN, 1);

        assertThat(r).hasStatusOk();
        assertThat(r).bodyJson().extractingPath("$.status").isEqualTo("IN_USE");
        assertThat(r).bodyJson().extractingPath("$.result").isEqualTo("OPENED");
        assertThat(r).bodyJson().extractingPath("$.roomId").isEqualTo(1);
        assertThat(status()).isEqualTo("IN_USE");
        assertThat(jdbc.sql("SELECT device_id || '|' || result || '|' || reservation_id FROM access_event")
                .query(String.class).list()).containsExactly("admin-button|OPENED|1");

        now("10:50");
        assertThat(mvc.post().uri("/api/reservations/1/checkout").header("X-User-Id", "1").exchange())
                .hasStatusOk();
        assertThat(status()).isEqualTo("CHECKED_OUT");
    }

    @Test
    @DisplayName("이미 입실한 예약을 다시 누르면 문만 다시 연다")
    void again() {
        now("10:32");
        assertThat(checkIn(ADMIN, 1)).hasStatusOk();

        assertThat(checkIn(ADMIN, 1)).hasStatusOk();
        assertThat(status()).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("관리자가 아니면 403 ADMIN_ONLY, 상태는 그대로")
    void notAdmin() {
        now("10:32");

        MvcTestResult r = checkIn(1, 1);

        assertThat(r).hasStatus(403);
        assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("ADMIN_ONLY");
        assertThat(status()).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("시작 10분 전보다 이르면 409이고, 문 앞 틀린 횟수는 올리지 않는다")
    void tooEarly() {
        now("10:15");

        MvcTestResult r = checkIn(ADMIN, 1);

        assertThat(r).hasStatus(409);
        assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_CHECKABLE");
        assertThat(status()).isEqualTo("BOOKED");
        assertThat(failed()).isZero();
    }

    @Test
    @DisplayName("입실 마감(시작 + 15분)이 지났거나 끝난 예약이면 409이고, 틀린 횟수는 그대로")
    void tooLateOrEnded() {
        // 트리거가 created_at(DB 실제 시각)으로 마감을 채워서, 테스트 시계 기준 10:45로 맞춘다
        jdbc.sql("UPDATE reservation SET checkin_deadline = CAST('2026-11-03T10:45:00+09:00' AS timestamptz) WHERE id = 1")
                .update();
        now("10:46");
        assertThat(checkIn(ADMIN, 1)).hasStatus(409);

        now("11:31");
        assertThat(checkIn(ADMIN, 1)).hasStatus(409);
        assertThat(status()).isEqualTo("BOOKED");
        assertThat(failed()).isZero();
    }

    @Test
    @DisplayName("없는 예약은 404")
    void missing() {
        now("10:32");

        assertThat(checkIn(ADMIN, 99)).hasStatus(404);
    }
}
