package kr.ac.chosun.mspace.appeal;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.appeal.application.AppealReviewUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 경고 철회 요청 (FR-RPT-105, Q-24 ①–③ 11/10 지영 "추천대로").
 * 회원 1 = 경고 받은 대표자, 2 = 신고자, 3 = 다른 학생. 판단기는 규칙 판단기(rules)
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WarningAppealApiTest {

    static final String TEXT = "퇴실할 때 컵은 다 버렸어요. 다시 봐 주세요.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AppealReviewUseCase reviews;

    @Autowired
    TransactionOperations tx;

    long used;      // 회원 1이 쓴 예약 (경고 근거)
    long reporting; // 회원 2가 신고할 때 쓰던 예약

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE warning_appeal, tidy_check, notification, report_suspension, user_suspension, user_warning, report,
                         reservation_photo, reservation_participant, reservation, users RESTART IDENTITY CASCADE
                """).update();
        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "a" + i + "@chosun.ac.kr").param("s", "202400" + (70 + i)).update();
        }
        used = reservation(1, 1, "5 hours");
        reporting = reservation(2, 2, "3 hours");
    }

    /** 회원 1–3이 함께 쓴 끝난 예약 (인원 = 참여자 수 검사가 커밋 때 돌아서 한 트랜잭션으로) */
    long reservation(int room, long owner, String ago) {
        return tx.execute(st -> {
            long id = jdbc.sql("""
                        INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                 checked_in_at, checked_out_at)
                        SELECT :room, :owner, t - CAST(:ago AS interval), t - CAST(:ago AS interval) + interval '1 hour', 3,
                               lpad(CAST(:owner AS text), 6, '0'), 'CHECKED_OUT',
                               t - CAST(:ago AS interval), t - CAST(:ago AS interval) + interval '1 hour'
                        FROM (SELECT date_trunc('hour', now()) AS t) x
                        RETURNING id
                        """)
                    .param("room", room).param("owner", owner).param("ago", ago).query(Long.class).single();
            for (long uid = 1; uid <= 3; uid++) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                                """).param("rid", id).param("uid", uid).param("owner", uid == owner).update();
            }
            return id;
        });
    }

    /** 학생 신고(어질러짐)가 사실로 판정돼 회원 1이 받은 위반 경고 */
    long userReportWarning(String ago) {
        long report = jdbc.sql("""
                        INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category, verdict, judged_at)
                        VALUES (1, 2, :rid, 'MESSY', 'CONFIRMED', now()) RETURNING id
                        """).param("rid", reporting).query(Long.class).single();
        return warning(1, "VIOLATION", report, used, ago);
    }

    /** 방 카메라 다시 확인(RECHECK)으로 회원 1이 받은 위반 경고 */
    long cameraWarning(double confidence) {
        long report = jdbc.sql("""
                        INSERT INTO report (room_id, source, target_reservation_id, category, verdict, judged_at)
                        VALUES (1, 'CAMERA', :rid, 'MESSY', 'CONFIRMED', now()) RETURNING id
                        """).param("rid", used).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO tidy_check (event_id, reservation_id, room_id, device_id, phase, result, items, confidences,
                                                left_items, report_id, observed_at, received_at)
                        VALUES ('ev-' || :rep, :rid, 1, 'cam-1', 'RECHECK', 'LEFT_ITEMS', '{cup}', ARRAY[CAST(:c AS real)],
                                '{}', :rep, now(), now())
                        """).param("rep", report).param("rid", used).param("c", confidence).update();
        return warning(1, "VIOLATION", report, used, "1 hour");
    }

    long warning(long user, String reason, long report, Long reservation, String ago) {
        return jdbc.sql("""
                        INSERT INTO user_warning (user_id, reason, report_id, reservation_id, created_at)
                        VALUES (:u, :r, :rep, :rid, now() - CAST(:ago AS interval)) RETURNING id
                        """)
                .param("u", user).param("r", reason).param("rep", report).param("rid", reservation).param("ago", ago)
                .query(Long.class).single();
    }

    MvcTestResult list(long user) {
        return mvc.get().uri("/api/penalties/me/warnings?days=30").header("X-User-Id", String.valueOf(user)).exchange();
    }

    MvcTestResult appeal(long user, long warning, String body) {
        return mvc.post().uri("/api/warnings/{id}/appeal", warning).header("X-User-Id", String.valueOf(user))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    MvcTestResult appeal(long user, long warning) {
        return appeal(user, warning, "{\"text\":\"%s\",\"evidence\":[\"CHECKOUT_PHOTO\"]}".formatted(TEXT));
    }

    int myWarnings(long user) {
        return assertThat(mvc.get().uri("/api/penalties/me").header("X-User-Id", String.valueOf(user)).exchange())
                .bodyJson().extractingPath("$.warnings").asNumber().actual().intValue();
    }

    @Test
    @DisplayName("내 경고 목록: 본인 것만, 근거 예약·이유·요청 마감(받은 뒤 7일, +09:00)을 주고 아직 요청 안 했으면 appeal은 null")
    void listMine() {
        long w = userReportWarning("1 hour");
        long report = jdbc.sql("""
                        INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category, verdict, judged_at)
                        VALUES (1, 2, :rid, 'BROKEN', 'FALSE', now()) RETURNING id
                        """).param("rid", reporting).query(Long.class).single();
        long falseReport = warning(2, "FALSE_REPORT", report, null, "2 hours");

        var mine = assertThat(list(1)).bodyJson();
        mine.extractingPath("$.length()").isEqualTo(1);
        mine.extractingPath("$[0].id").isEqualTo((int) w);
        mine.extractingPath("$[0].reason").isEqualTo("VIOLATION");
        mine.extractingPath("$[0].reportType").isEqualTo("MESSY");
        mine.extractingPath("$[0].reservationId").isEqualTo((int) used);
        mine.extractingPath("$[0].why").isEqualTo("이용하신 방의 어질러짐 신고가 사실로 판정됐어요");
        mine.extractingPath("$[0].appealableUntil").asString().endsWith("+09:00");
        mine.extractingPath("$[0].appeal").isNull();

        // 허위 신고 경고는 신고할 때 쓰던 예약을 근거로 보여 준다
        var other = assertThat(list(2)).bodyJson();
        other.extractingPath("$[0].id").isEqualTo((int) falseReport);
        other.extractingPath("$[0].reservationId").isEqualTo((int) reporting);
        other.extractingPath("$[0].why").isEqualTo("비품 고장 신고가 허위로 판정됐어요");
        assertThat(list(3)).bodyJson().extractingPath("$.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("철회 요청 201 → PENDING. 요청 중에도 경고는 센다(①). 두 번째는 409 WARNING_APPEAL_DUPLICATE")
    void appealOnce() {
        long w = userReportWarning("1 hour");

        MvcTestResult first = appeal(1, w);
        assertThat(first).hasStatus(201);
        assertThat(first).bodyJson().extractingPath("$.appeal.status").isEqualTo("PENDING");
        assertThat(first).bodyJson().extractingPath("$.appeal.text").isEqualTo(TEXT);
        assertThat(myWarnings(1)).isEqualTo(1);

        MvcTestResult again = appeal(1, w);
        assertThat(again).hasStatus(409);
        assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("WARNING_APPEAL_DUPLICATE");
    }

    @Test
    @DisplayName("남의 경고 404, 받은 지 7일 지난 경고 409 WARNING_APPEAL_EXPIRED")
    void notMineOrExpired() {
        long w = userReportWarning("1 hour");
        long old = userReportWarning("7 days 1 minute");

        assertThat(appeal(3, w)).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("WARNING_NOT_FOUND");
        assertThat(appeal(1, old)).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("WARNING_APPEAL_EXPIRED");
    }

    @Test
    @DisplayName("사유가 10자(글자 기준) 미만이거나, 학번·전화번호 같은 긴 숫자가 있거나, 모르는 기록 종류·빈 값이면 400")
    void invalidText() {
        long w = userReportWarning("1 hour");

        assertThat(appeal(1, w, "{\"text\":\"억울해요\"}")).hasStatus(400);
        assertThat(appeal(1, w, "{\"text\":\"제 학번은 20240071 이고 억울해요\"}")).hasStatus(400);
        assertThat(appeal(1, w, "{\"text\":\"연락은 010-1234-5678 로 주세요 억울해요\"}")).hasStatus(400);
        assertThat(appeal(1, w, "{\"text\":\"%s\",\"evidence\":[\"CCTV\"]}".formatted(TEXT))).hasStatus(400);
        assertThat(appeal(1, w, "{\"text\":\"%s\",\"evidence\":[null]}".formatted(TEXT))).hasStatus(400);
        // 이모지 5개는 Java 길이로는 10이지만 글자로는 5개라 10자 미만
        assertThat(appeal(1, w, "{\"text\":\"😀😀😀😀😀\"}")).hasStatus(400);
        // 방 번호·시각 같은 짧은 숫자는 된다
        assertThat(appeal(1, w, "{\"text\":\"1번 방 14:00 예약인데 컵을 다 버렸어요\"}")).hasStatus(201);
    }

    @Test
    @DisplayName("학생 신고 경고는 규칙 판단기가 근거를 읽지 못해 철회(③). 경고 수에서 빠지고, 판단 기록과 결과 알림을 남긴다")
    void userReportWithdrawn() {
        long w = userReportWarning("1 hour");
        appeal(1, w);

        assertThat(reviews.reviewPending()).isEqualTo(1);

        var json = assertThat(list(1)).bodyJson();
        json.extractingPath("$[0].appeal.status").isEqualTo("WITHDRAWN");
        json.extractingPath("$[0].appeal.decidedAt").asString().endsWith("+09:00");
        json.extractingPath("$[0].appeal.answer").asString().contains("벌칙을 주지 않는");
        assertThat(myWarnings(1)).isEqualTo(0);
        var record = jdbc.sql("SELECT reviewer, prompt_version, confidence FROM warning_appeal")
                .query((rs, n) -> rs.getString(1) + "/" + rs.getString(2) + "/" + rs.getFloat(3)).single();
        assertThat(record).isEqualTo("rules/rules-v1/0.0");
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE user_id = 1 AND kind = 'WARNING_APPEAL_RESULT' AND reservation_id = :r")
                .param("r", used).query(Integer.class).single()).isEqualTo(1);
        // 다시 돌려도 끝난 요청은 다시 보지 않는다
        assertThat(reviews.reviewPending()).isZero();
    }

    @Test
    @DisplayName("카메라 경고는 다시 확인 때 확신도가 기준(0.8) 이상이면 유지. 경고 수는 그대로")
    void cameraKept() {
        long w = cameraWarning(0.93);
        appeal(1, w);

        reviews.reviewPending();

        var json = assertThat(list(1)).bodyJson();
        json.extractingPath("$[0].why").isEqualTo("퇴실 뒤 방 카메라가 다시 봤을 때도 두고 간 물건이 남아 있었어요");
        json.extractingPath("$[0].appeal.status").isEqualTo("KEPT");
        json.extractingPath("$[0].appeal.answer").asString().contains("0.93");
        assertThat(myWarnings(1)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT evidence_used[1] FROM warning_appeal").query(String.class).single()).isEqualTo("TIDY_CHECK");
    }

    @Test
    @DisplayName("철회되면 그 경고로 걸린 이용 정지를 지금 풀고(②), 남은 경고는 다시 센다")
    void withdrawLiftsSuspension() {
        long w1 = userReportWarning("3 hours");
        long w2 = userReportWarning("2 hours");
        long w3 = userReportWarning("1 hour");
        jdbc.sql("""
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at, warning_id)
                VALUES (1, 'WARNINGS', now() - interval '1 hour', now() + interval '14 days', :w)
                """).param("w", w3).update();
        assertThat(myWarnings(1)).isZero();

        appeal(1, w2);
        reviews.reviewPending();

        var me = assertThat(mvc.get().uri("/api/penalties/me").header("X-User-Id", "1").exchange()).bodyJson();
        me.extractingPath("$.suspendedUntil").isNull();
        me.extractingPath("$.warnings").isEqualTo(2);
        assertThat(jdbc.sql("SELECT body FROM notification WHERE kind = 'WARNING_APPEAL_RESULT'").query(String.class).single())
                .contains("정지도 지금 풀었어요");
        assertThat(w1).isLessThan(w2);
    }

    @Test
    @DisplayName("정지를 건 경고보다 앞 정지 때 이미 센 경고가 철회되면 지금 정지는 그대로 둔다")
    void olderWarningKeepsNewSuspension() {
        long old = userReportWarning("6 days");
        jdbc.sql("""
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at, warning_id)
                VALUES (1, 'WARNINGS', now() - interval '6 days', now() - interval '5 days', :w)
                """).param("w", old).update();
        userReportWarning("3 hours");
        userReportWarning("2 hours");
        long w3 = userReportWarning("1 hour");
        jdbc.sql("""
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at, warning_id)
                VALUES (1, 'WARNINGS', now() - interval '1 hour', now() + interval '14 days', :w)
                """).param("w", w3).update();

        appeal(1, old);
        reviews.reviewPending();

        assertThat(mvc.get().uri("/api/penalties/me").header("X-User-Id", "1").exchange())
                .bodyJson().extractingPath("$.suspendedUntil").asString().endsWith("+09:00");
    }

    // ---- 관리자 경고 철회 (11/12 지영 "이유 적고 기록 남기기") ----

    long admin() {
        return jdbc.sql("""
                INSERT INTO users (email, student_id, name, verified_at, role)
                VALUES ('admin@chosun.ac.kr', '20240099', '홍길동', now(), 'ADMIN') RETURNING id
                """).query(Long.class).single();
    }

    MvcTestResult adminWithdraw(long user, long warning, String reason) {
        return mvc.post().uri("/api/admin/warnings/{id}/withdraw", warning).header("X-User-Id", String.valueOf(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"%s\"}".formatted(reason)).exchange();
    }

    @Test
    @DisplayName("관리자 철회: 경고를 세지 않고, 걸린 정지를 풀고, 누가·왜를 남기고, 학생에게 이유와 함께 알린다")
    void adminWithdrawLiftsAndRecords() {
        // given: 경고 3번으로 정지된 학생
        long admin = admin();
        userReportWarning("3 hours");
        userReportWarning("2 hours");
        long w3 = userReportWarning("1 hour");
        jdbc.sql("""
                INSERT INTO user_suspension (user_id, reason, starts_at, ends_at, warning_id)
                VALUES (1, 'WARNINGS', now() - interval '1 hour', now() + interval '14 days', :w)
                """).param("w", w3).update();

        // when
        MvcTestResult r = adminWithdraw(admin, w3, "카메라 오인으로 확인됨");

        // then
        assertThat(r).hasStatusOk();
        assertThat(r).bodyJson().extractingPath("$.suspensionLifted").isEqualTo(true);
        assertThat(r).bodyJson().extractingPath("$.appealClosed").isEqualTo(false);
        var me = assertThat(mvc.get().uri("/api/penalties/me").header("X-User-Id", "1").exchange()).bodyJson();
        me.extractingPath("$.suspendedUntil").isNull();
        me.extractingPath("$.warnings").isEqualTo(2);
        assertThat(jdbc.sql("SELECT withdrawn_by FROM user_warning WHERE id = :id").param("id", w3).query(Long.class).single())
                .isEqualTo(admin);
        assertThat(jdbc.sql("SELECT withdraw_reason FROM user_warning WHERE id = :id").param("id", w3).query(String.class)
                .single()).isEqualTo("카메라 오인으로 확인됨");
        assertThat(jdbc.sql("SELECT body FROM notification WHERE user_id = 1 AND kind = 'WARNING_APPEAL_RESULT'")
                .query(String.class).single()).contains("관리자가 다시 확인").contains("카메라 오인으로 확인됨")
                .contains("정지도 지금 풀었어요");
    }

    @Test
    @DisplayName("관리자 철회: 대기 중인 학생 철회 요청은 철회로 닫고(판단기 admin), AI 판단은 그 요청을 다시 보지 않는다")
    void adminWithdrawClosesPendingAppeal() {
        // given
        long admin = admin();
        long w = userReportWarning("1 hour");
        assertThat(appeal(1, w)).hasStatus(HttpStatus.CREATED);

        // when
        MvcTestResult r = adminWithdraw(admin, w, "직접 확인함");

        // then
        assertThat(r).bodyJson().extractingPath("$.appealClosed").isEqualTo(true);
        assertThat(jdbc.sql("SELECT status || ' ' || reviewer FROM warning_appeal WHERE warning_id = :w").param("w", w)
                .query(String.class).single()).isEqualTo("WITHDRAWN admin");
        assertThat(reviews.reviewPending()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE kind = 'WARNING_APPEAL_RESULT'").query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    @DisplayName("관리자가 철회한 경고에는 학생이 철회 요청을 보낼 수 없다(409 WARNING_ALREADY_WITHDRAWN) (재웅 #123)")
    void appealAfterAdminWithdrawRejected() {
        // given
        long admin = admin();
        long w = userReportWarning("1 hour");
        assertThat(adminWithdraw(admin, w, "직접 확인함")).hasStatusOk();

        // when
        MvcTestResult r = appeal(1, w);

        // then
        assertThat(r).hasStatus(HttpStatus.CONFLICT);
        assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("WARNING_ALREADY_WITHDRAWN");
        assertThat(jdbc.sql("SELECT count(*) FROM warning_appeal").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("관리자 철회와 엇갈려 대기로 남은 요청은 AI 판단이 결과와 상관없이 철회로 닫고, 학생에게 다시 알리지 않는다 (재웅 #123)")
    void pendingLeftAfterAdminWithdrawClosedSilently() {
        // given: 관리자 철회가 대기 요청을 찾은 뒤 학생 요청이 먼저 들어간 경우 (요청 줄을 직접 넣어 만든다)
        long admin = admin();
        long w = userReportWarning("1 hour");
        assertThat(adminWithdraw(admin, w, "직접 확인함")).hasStatusOk();
        jdbc.sql("""
                INSERT INTO warning_appeal (warning_id, user_id, text, evidence, created_at)
                VALUES (:w, 1, :text, '{}', now())
                """).param("w", w).param("text", TEXT).update();

        // when
        reviews.reviewPending();

        // then
        assertThat(jdbc.sql("SELECT status || ' ' || reviewer FROM warning_appeal WHERE warning_id = :w").param("w", w)
                .query(String.class).single()).isEqualTo("WITHDRAWN withdrawn");
        assertThat(jdbc.sql("SELECT title FROM notification WHERE user_id = 1 AND kind = 'WARNING_APPEAL_RESULT'")
                .query(String.class).list()).containsExactly("경고가 철회됐어요");
    }

    @Test
    @DisplayName("관리자 철회: 관리자가 아니면 403, 없는 경고 404, 두 번째는 409, 이유가 없거나 200자 넘으면 400(이모지는 1자)")
    void adminWithdrawErrors() {
        long admin = admin();
        long w = userReportWarning("1 hour");

        assertThat(adminWithdraw(1, w, "내가 풀래요")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(adminWithdraw(admin, w, "  ")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(adminWithdraw(admin, w, "가".repeat(201))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(adminWithdraw(admin, 9999, "없음")).hasStatus(HttpStatus.NOT_FOUND);
        // 이모지 100개 + 5자 = 105자. String.length()로 세면 205라 막혔을 것
        String first = "😀".repeat(100) + "첫 번째";
        assertThat(adminWithdraw(admin, w, first)).hasStatusOk();
        MvcTestResult again = adminWithdraw(admin, w, "두 번째");
        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("WARNING_ALREADY_WITHDRAWN");
        assertThat(jdbc.sql("SELECT withdraw_reason FROM user_warning WHERE id = :id").param("id", w).query(String.class)
                .single()).isEqualTo(first);
    }
}
