package kr.ac.chosun.mspace.tidy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import kr.ac.chosun.mspace.tidy.application.RetrievePassUseCase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionOperations;

/**
 * "찾으러 갈게요" 10분 QR (FR-DEV-105, 11/10 지영). 1번 방: 회원 1(대표자)·2·3이 1시간 전에 퇴실(ended),
 * 지금은 회원 4가 사용 중(now). 다음 팀이 쓰고 있어도 퇴실 뒤 3시간 안이면 QR을 준다.
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RetrievePassApiTest {

    static final String TOKEN = "Bearer test-device-token";
    static final String UMBRELLA = "[{\"name\":\"umbrella\",\"confidence\":0.93}]";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    @Autowired
    RetrievePassUseCase retrieves;

    long ended;
    long now;
    int eventSeq;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE retrieve_pass, tidy_check, access_event, notification, reservation, reservation_participant,
                         users RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("UPDATE keypad_lock SET failed_count = 0, locked_until = NULL").update();
        for (int i = 1; i <= 7; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "t" + i + "@chosun.ac.kr").param("s", "202600" + (10 + i)).update();
        }
        ended = tx.execute(st -> insertWithPeople("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                         checked_in_at, checked_out_at)
                SELECT 1, 1, t - interval '2 hours', t - interval '1 hour', 3, '111111', 'CHECKED_OUT',
                       t - interval '2 hours', now() - interval '1 hour'
                FROM (SELECT date_trunc('hour', now()) AS t) x
                RETURNING id
                """, 1, 2, 3));
        now = tx.execute(st -> insertWithPeople("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status, checked_in_at)
                SELECT 1, 4, t, t + interval '1 hour', 3, '222222', 'IN_USE', t
                FROM (SELECT date_trunc('hour', now()) AS t) x
                RETURNING id
                """, 4, 6, 7));
    }

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

    void first(String result, String items) {
        assertThat(mvc.post().uri("/api/rooms/1/tidy-checks").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"f-%d","reservationId":%d,"deviceId":"jetson-b","phase":"FIRST","result":"%s",
                         "items":%s,"observedAt":"2026-11-10T15:01:00+09:00"}"""
                        .formatted(ended, ended, result, items))).hasStatusOk();
    }

    MvcTestResult pass(long userId, long reservationId) {
        return mvc.post().uri("/api/reservations/{id}/retrieve-pass", reservationId)
                .header("X-User-Id", String.valueOf(userId)).exchange();
    }

    MvcTestResult door(String eventId, int roomId, String code) {
        return mvc.post().uri("/api/access/verify").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"%s","roomId":%d,"deviceId":"qr-%d","code":"%s"}"""
                        .formatted(eventId, roomId, roomId, code)).exchange();
    }

    MvcTestResult door(String code) {
        return door("d-" + (++eventSeq), 1, code);
    }

    static String body(MvcTestResult r) {
        try {
            return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    String code(MvcTestResult r) {
        return JsonPath.read(body(r), "$.code");
    }

    @Test
    @DisplayName("두고 간 물건이 있으면 대표자가 10분 QR을 받고, 다음 팀이 쓰는 중에도 문이 RETRIEVE로 열린다")
    void ownerGetsPassAndOpens() {
        // given
        first("LEFT_ITEMS", UMBRELLA);

        // when
        MvcTestResult r = pass(1, ended);

        // then
        assertThat(r).hasStatusOk().bodyJson().satisfies(j -> {
            assertThat(j).extractingPath("$.reservationId").isEqualTo((int) ended);
            assertThat(j).extractingPath("$.roomId").isEqualTo(1);
        });
        String code = code(r);
        assertThat(code).matches("[0-9]{6}");
        assertThat(JsonPath.<String>read(body(r), "$.qrText")).isEqualTo("MSPACE:1:" + code);
        OffsetDateTime expires = OffsetDateTime.parse(JsonPath.read(body(r), "$.expiresAt"));
        assertThat(expires.getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
        assertThat(Duration.between(OffsetDateTime.now(), expires).toMinutes()).isBetween(9L, 10L);

        assertThat(door(code)).hasStatusOk().bodyJson().satisfies(j -> {
            assertThat(j).extractingPath("$.result").isEqualTo("OPENED");
            assertThat(j).extractingPath("$.purpose").isEqualTo("RETRIEVE");
        });
        // 10분 안에는 여러 번 열린다. 체크인은 하지 않는다
        assertThat(door(code)).hasStatusOk().bodyJson().extractingPath("$.purpose").isEqualTo("RETRIEVE");
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", ended)
                .query((rs, n) -> rs.getString(1)).single()).isEqualTo("CHECKED_OUT");
        assertThat(jdbc.sql("SELECT first_used_at IS NOT NULL FROM retrieve_pass").query((rs, n) -> rs.getBoolean(1))
                .single()).isTrue();
        assertThat(jdbc.sql("SELECT reservation_id FROM access_event WHERE purpose = 'RETRIEVE' LIMIT 1")
                .query((rs, n) -> rs.getLong(1)).single()).isEqualTo(ended);
    }

    @Test
    @DisplayName("보통 입실 번호는 purpose 없이 열리고, 같은 eventId 재전송은 처음 RETRIEVE를 그대로 돌려준다")
    void normalEntryAndReplay() {
        // given
        first("LEFT_ITEMS", UMBRELLA);
        String code = code(pass(1, ended));

        // when, then
        assertThat(body(door("222222"))).isEqualTo("{\"result\":\"OPENED\"}");
        assertThat(door("same", 1, code)).bodyJson().extractingPath("$.purpose").isEqualTo("RETRIEVE");
        assertThat(door("same", 1, code)).bodyJson().extractingPath("$.purpose").isEqualTo("RETRIEVE");
        // 다른 방 문에서는 열리지 않는다
        assertThat(door("other", 2, code)).bodyJson().extractingPath("$.reason").isEqualTo("WRONG_CODE");
    }

    @Test
    @DisplayName("찾으러 온 QR로 열려도 틀린 횟수는 그대로라, 사이사이 틀린 번호가 5번 쌓이면 잠긴다")
    void retrieveDoesNotResetFailures() {
        // given
        first("LEFT_ITEMS", UMBRELLA);
        String code = code(pass(1, ended));
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 4; i++) {
            assertThat(door(wrong)).bodyJson().extractingPath("$.reason").isEqualTo("WRONG_CODE");
        }
        assertThat(door(code)).bodyJson().extractingPath("$.purpose").isEqualTo("RETRIEVE");

        // when
        door(wrong);

        // then
        assertThat(door(code)).bodyJson().extractingPath("$.reason").isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("참여자도 받을 수 있고, 살아 있는 동안 다시 누르면 같은 QR이다")
    void participantGetsSamePass() {
        // given
        first("LEFT_ITEMS", UMBRELLA);
        String owners = code(pass(1, ended));

        // when
        MvcTestResult again = pass(3, ended);

        // then
        assertThat(again).hasStatusOk();
        assertThat(code(again)).isEqualTo(owners);
        assertThat(jdbc.sql("SELECT count(*) FROM retrieve_pass").query((rs, n) -> rs.getLong(1)).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("문을 안 열고 10분이 지나면 그 QR은 안 열리고, 3시간 안이면 다시 눌러 새 QR을 받는다")
    void expiredPass() {
        // given
        first("LEFT_ITEMS", UMBRELLA);
        String old = code(pass(1, ended));
        jdbc.sql("UPDATE retrieve_pass SET issued_at = now() - interval '11 minutes', expires_at = now() - interval '1 minute'")
                .update();

        // when, then
        assertThat(door(old)).bodyJson().extractingPath("$.reason").isEqualTo("WRONG_CODE");
        assertThat(pass(1, ended)).hasStatusOk();
        assertThat(jdbc.sql("SELECT count(*) FROM retrieve_pass").query((rs, n) -> rs.getLong(1)).single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("QR로 문을 한 번 열었으면, 10분 안에는 같은 QR을 다시 주지만 닫힌 뒤에는 409 RETRIEVE_ALREADY_USED")
    void usedPassNotReissued() {
        // given
        first("LEFT_ITEMS", UMBRELLA);
        String code = code(pass(1, ended));
        assertThat(door(code)).bodyJson().extractingPath("$.purpose").isEqualTo("RETRIEVE");

        // when, then: 살아 있는 동안은 같은 QR (참여자가 눌러도)
        assertThat(code(pass(2, ended))).isEqualTo(code);
        assertThat(door(code)).bodyJson().extractingPath("$.purpose").isEqualTo("RETRIEVE");

        // when, then: 닫힌 뒤에는 새 QR 없음
        jdbc.sql("UPDATE retrieve_pass SET issued_at = now() - interval '11 minutes', expires_at = now() - interval '1 minute'")
                .update();
        assertThat(pass(1, ended)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("RETRIEVE_ALREADY_USED");
        assertThat(pass(3, ended)).hasStatus(409);
        assertThat(jdbc.sql("SELECT count(*) FROM retrieve_pass").query((rs, n) -> rs.getLong(1)).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("남의 예약·없는 예약은 404, 아직 사용 중이면 409")
    void notMine() {
        // given
        first("LEFT_ITEMS", UMBRELLA);

        // when, then
        assertThat(pass(4, ended)).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_FOUND");
        assertThat(pass(1, 999)).hasStatus(404);
        assertThat(pass(4, now)).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_ENDED");
        assertThat(mvc.post().uri("/api/reservations/{id}/retrieve-pass", ended)).hasStatus(401);
    }

    @Test
    @DisplayName("카메라가 두고 간 물건을 찾지 않았으면 409 RETRIEVE_NOT_NEEDED")
    void nothingLeft() {
        // 아직 FIRST 결과 없음
        assertThat(pass(1, ended)).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("RETRIEVE_NOT_NEEDED");

        // FIRST가 CLEAN
        first("CLEAN", "[]");
        assertThat(pass(1, ended)).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("RETRIEVE_NOT_NEEDED");
    }

    @Test
    @DisplayName("퇴실 뒤 3시간이 지나면 409 RETRIEVE_TOO_LATE, 바로 전이면 받는다")
    void tooLate() {
        // given
        first("LEFT_ITEMS", UMBRELLA);
        jdbc.sql("UPDATE reservation SET checked_out_at = now() - interval '179 minutes' WHERE id = :id")
                .param("id", ended).update();

        // when, then
        assertThat(pass(1, ended)).hasStatusOk();

        jdbc.sql("UPDATE reservation SET checked_out_at = now() - interval '181 minutes' WHERE id = :id")
                .param("id", ended).update();
        jdbc.sql("DELETE FROM retrieve_pass").update();
        assertThat(pass(1, ended)).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("RETRIEVE_TOO_LATE");
    }

    @Test
    @DisplayName("QR 사용은 예약 행 잠금을 기다려, 자동 판정·다시 확인의 경고와 동시에 확정되지 않는다 (CodeRabbit #118)")
    void useWaitsForReservationLock() throws Exception {
        // given: QR을 받고, 다른 트랜잭션(자동 판정이라 치고)이 그 예약 행을 잡고 있다
        first("LEFT_ITEMS", UMBRELLA);
        String code = code(pass(1, ended));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> judge = CompletableFuture.runAsync(() -> tx.executeWithoutResult(st -> {
            jdbc.sql("SELECT id FROM reservation WHERE id = :id FOR UPDATE").param("id", ended)
                    .query((rs, n) -> rs.getLong(1)).single();
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // when
        CompletableFuture<?> use = CompletableFuture.supplyAsync(() -> retrieves.open(1, code));

        // then: 잠금이 풀릴 때까지 기다렸다가 쓴다
        waitUntilBlockedOnLock();
        assertThat(use).isNotDone();
        release.countDown();
        judge.get(10, TimeUnit.SECONDS);
        assertThat(use.get(10, TimeUnit.SECONDS)).isEqualTo(Optional.of(ended));
        assertThat(jdbc.sql("SELECT first_used_at IS NOT NULL FROM retrieve_pass").query((rs, n) -> rs.getBoolean(1))
                .single()).isTrue();
    }

    @Test
    @DisplayName("예약 잠금을 기다리는 동안 QR이 닫히면 쓰지 않는다 (CodeRabbit #118)")
    void expiredWhileWaitingNotUsed() throws Exception {
        // given: 다른 트랜잭션이 예약 행을 잡고 있는 동안 QR이 닫힌다
        first("LEFT_ITEMS", UMBRELLA);
        String code = code(pass(1, ended));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> judge = CompletableFuture.runAsync(() -> tx.executeWithoutResult(st -> {
            jdbc.sql("SELECT id FROM reservation WHERE id = :id FOR UPDATE").param("id", ended)
                    .query((rs, n) -> rs.getLong(1)).single();
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            jdbc.sql("UPDATE retrieve_pass SET issued_at = now() - interval '11 minutes', expires_at = now() - interval '1 second'").update();
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // when
        CompletableFuture<?> use = CompletableFuture.supplyAsync(() -> retrieves.open(1, code));
        waitUntilBlockedOnLock();
        release.countDown();
        judge.get(10, TimeUnit.SECONDS);

        // then
        assertThat(use.get(10, TimeUnit.SECONDS)).isEqualTo(Optional.empty());
        assertThat(jdbc.sql("SELECT first_used_at IS NULL FROM retrieve_pass").query((rs, n) -> rs.getBoolean(1))
                .single()).isTrue();
    }

    /**
     * QR 사용이 QR을 찾은 뒤 예약 잠금에서 기다리고 있는지 DB에서 확인한다 (시간에 기대지 않게).
     * 잠금을 잡은 쪽은 기다리지 않으므로, 이 SQL로 Lock을 기다리는 세션은 QR 사용(lockReservation)뿐이다. 못 찾으면 실패한다
     */
    void waitUntilBlockedOnLock() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            boolean waiting = jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                                           WHERE datname = current_database() AND pid <> pg_backend_pid()
                                             AND wait_event_type = 'Lock'
                                             AND query = 'SELECT id FROM reservation WHERE id = $1 FOR UPDATE')
                            """)
                    .query((rs, n) -> rs.getBoolean(1)).single();
            if (waiting) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("QR 사용이 예약 잠금에서 기다리지 않음");
    }
}
