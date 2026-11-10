package kr.ac.chosun.mspace.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.photo.application.PhotoUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 방 카메라 사진 (FR-DEV-105, Q-27, 11/10 지영 "core에 올려 30일 보관").
 * 1번 방: 회원 1(대표자)·2·3의 예약 A가 먼저 퇴실, 회원 4·5·6의 예약 B가 그 뒤 퇴실(마지막). 회원 7은 관리자.
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CameraPhotoApiTest {

    static final String TOKEN = "Bearer test-device-token";
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    @Autowired
    PhotoUseCase photos;

    long older;
    long latest;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE tidy_photo, reservation_photo, reservation, reservation_participant, users RESTART IDENTITY CASCADE")
                .update();
        for (int i = 1; i <= 7; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "c" + i + "@chosun.ac.kr").param("s", "202600" + (20 + i)).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = 7").update();
        older = tx.execute(st -> ended("4 hours", "3 hours", 1, 2, 3));
        latest = tx.execute(st -> ended("2 hours", "1 hour", 4, 5, 6));
    }

    long ended(String startAgo, String endAgo, long... userIds) {
        long id = jdbc.sql("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                         checked_in_at, checked_out_at)
                SELECT 1, :owner, t - CAST(:s AS interval), t - CAST(:e AS interval), 3, :code, 'CHECKED_OUT',
                       t - CAST(:s AS interval), t - CAST(:e AS interval)
                FROM (SELECT date_trunc('hour', now()) AS t) x
                RETURNING id
                """).param("owner", userIds[0]).param("s", startAgo).param("e", endAgo)
                .param("code", "33333" + userIds[0]).query((rs, n) -> rs.getLong(1)).single();
        for (int i = 0; i < userIds.length; i++) {
            jdbc.sql("""
                    INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                    SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                    """).param("rid", id).param("uid", userIds[i]).param("owner", i == 0).update();
        }
        return id;
    }

    MvcTestResult upload(String token, int roomId, Long reservationId, String phase, byte[] data) {
        var req = mvc.post().uri("/api/rooms/{roomId}/tidy-photos", roomId).multipart();
        if (token != null) {
            req = req.header("Authorization", token);
        }
        if (data != null) {
            req = req.file(new MockMultipartFile("file", "room.jpg", "image/jpeg", data));
        }
        if (reservationId != null) {
            req = req.param("reservationId", String.valueOf(reservationId));
        }
        if (phase != null) {
            req = req.param("phase", phase);
        }
        return req.param("deviceId", "jetson-b").exchange();
    }

    MvcTestResult view(long reservationId, long user, String phase) {
        return mvc.get().uri("/api/reservations/{id}/tidy-photos/{phase}", reservationId, phase)
                .header("X-User-Id", String.valueOf(user)).exchange();
    }

    @Test
    @DisplayName("젯슨이 마지막 퇴실 예약의 FIRST·RECHECK 사진을 1장씩 올리고, 그 예약 사람과 관리자만 본다")
    void uploadAndView() throws Exception {
        // when
        MvcTestResult r = upload(TOKEN, 1, latest, "FIRST", JPEG);

        // then
        assertThat(r).hasStatus(201).bodyJson().satisfies(j -> {
            assertThat(j).extractingPath("$.reservationId").isEqualTo((int) latest);
            assertThat(j).extractingPath("$.phase").isEqualTo("FIRST");
            assertThat(j).extractingPath("$.sizeBytes").isEqualTo(JPEG.length);
        });
        assertThat(upload(TOKEN, 1, latest, "recheck", JPEG)).hasStatus(201);

        MvcTestResult mine = view(latest, 5, "FIRST");
        assertThat(mine).hasStatusOk().hasHeader("Cache-Control", "no-store");
        assertThat(mine.getResponse().getContentAsByteArray()).isEqualTo(JPEG);
        assertThat(view(latest, 7, "RECHECK")).hasStatusOk();
        assertThat(view(latest, 1, "FIRST")).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("PHOTO_NOT_FOUND");
        assertThat(view(older, 1, "FIRST")).hasStatus(404);
    }

    @Test
    @DisplayName("같은 단계 두 번째는 409, 지난 예약에는 409 TIDY_CHECK_NOT_LATEST, 다른 방 예약은 404")
    void rejects() {
        // given
        upload(TOKEN, 1, latest, "FIRST", JPEG);

        // when, then
        assertThat(upload(TOKEN, 1, latest, "FIRST", JPEG)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("PHOTO_ALREADY_UPLOADED");
        assertThat(upload(TOKEN, 1, older, "FIRST", JPEG)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("TIDY_CHECK_NOT_LATEST");
        assertThat(upload(TOKEN, 2, latest, "FIRST", JPEG)).hasStatus(404).bodyJson().extractingPath("$.code")
                .isEqualTo("RESERVATION_NOT_FOUND");
    }

    @Test
    @DisplayName("토큰 없으면 401, 단계·파일·예약 빠지거나 JPEG·PNG 아니면 400, 2MB 넘으면 400 PHOTO_TOO_LARGE")
    void invalid() {
        assertThat(upload(null, 1, latest, "FIRST", JPEG)).hasStatus(401);
        assertThat(upload("Bearer wrong", 1, latest, "FIRST", JPEG)).hasStatus(401);
        assertThat(upload(TOKEN, 1, latest, null, JPEG)).hasStatus(400).bodyJson().extractingPath("$.code")
                .isEqualTo("PHOTO_INVALID");
        assertThat(upload(TOKEN, 1, latest, "BASELINE", JPEG)).hasStatus(400);
        assertThat(upload(TOKEN, 1, latest, "FIRST", null)).hasStatus(400);
        assertThat(upload(TOKEN, 1, null, "FIRST", JPEG)).hasStatus(400);
        assertThat(upload(TOKEN, 1, latest, "FIRST", new byte[] {1, 2, 3})).hasStatus(400);
        assertThat(upload(TOKEN, 1, latest, "FIRST", Arrays.copyOf(JPEG, 2 * 1024 * 1024 + 1))).hasStatus(400)
                .bodyJson().extractingPath("$.code").isEqualTo("PHOTO_TOO_LARGE");
        assertThat(upload(TOKEN, 1, latest, "FIRST", Arrays.copyOf(JPEG, 2 * 1024 * 1024))).hasStatus(201);
    }

    @Test
    @DisplayName("올린 지 30일 지난 카메라 사진은 학생 사진과 같은 정리가 지운다")
    void cleanup() {
        // given
        upload(TOKEN, 1, latest, "FIRST", JPEG);
        upload(TOKEN, 1, latest, "RECHECK", JPEG);
        jdbc.sql("UPDATE tidy_photo SET created_at = now() - interval '31 days' WHERE phase = 'FIRST'").update();

        // when
        int deleted = photos.deleteExpired();

        // then
        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.sql("SELECT phase FROM tidy_photo").query((rs, n) -> rs.getString(1)).list())
                .containsExactly("RECHECK");
    }
}
