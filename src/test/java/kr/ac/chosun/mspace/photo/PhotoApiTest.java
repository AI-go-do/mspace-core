package kr.ac.chosun.mspace.photo;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.photo.application.PhotoUseCase;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 사진 체크인·아웃 (FR-RPT-102). 기본 예약: 1번 방 10:30–12:30, 대표자 1번 + 참여자 2·3번.
 * 4번은 남, 5번은 관리자. 시계는 2030년(다른 예약 테스트와 같은 이유).
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PhotoApiTest.TestBeans.class})
class PhotoApiTest {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9, 9};

    static class SettableClock extends Clock {
        volatile Instant now;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock();
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SettableClock clock;

    @Autowired
    PhotoUseCase photos;

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, reservation_photo, users RESTART IDENTITY CASCADE")
                .update();
        for (int i = 1; i <= 5; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = 5").update();
    }

    static String iso(String time) {
        return "2030-03-05T" + time + ":00+09:00";
    }

    void at(String time) {
        clock.now = OffsetDateTime.parse(iso(time)).toInstant();
    }

    /** 대표자 1번(참여자 2·3번)이 1번 방 10:30–12:30을 잡고 10:30에 입실, 시계는 10:40 */
    long bookAndCheckIn() {
        long id = book();
        jdbc.sql("UPDATE reservation SET status = 'IN_USE', checked_in_at = start_at WHERE id = :id").param("id", id)
                .update();
        at("10:40");
        return id;
    }

    /** 잡기만 한 예약(BOOKED), 시계는 10:40 */
    long book() {
        at("10:00");
        MvcTestResult r = mvc.post().uri("/api/reservations").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"%s","endAt":"%s","participantStudentIds":["20230002","20230003"]}"""
                        .formatted(iso("10:30"), iso("12:30")))
                .exchange();
        assertThat(r).hasStatus(201);
        long id = ((Number) JsonPath.read(body(r), "$.id")).longValue();
        at("10:40");
        return id;
    }

    /** 사용 중 예약을 time에 퇴실한 것으로 둔다 */
    void checkedOutAt(long id, String time) {
        jdbc.sql("UPDATE reservation SET status = 'CHECKED_OUT', checked_out_at = :t WHERE id = :id")
                .param("t", OffsetDateTime.parse(iso(time))).param("id", id).update();
    }

    MvcTestResult upload(long id, long user, String kind, byte[] data) {
        var req = mvc.post().uri("/api/reservations/{id}/photos", id).header("X-User-Id", String.valueOf(user))
                .multipart();
        if (data != null) {
            req = req.file(new MockMultipartFile("file", "p.jpg", "image/jpeg", data));
        }
        if (kind != null) {
            req = req.param("kind", kind);
        }
        return req.exchange();
    }

    MvcTestResult view(long id, long user, String kind) {
        return mvc.get().uri("/api/reservations/{id}/photos/{kind}", id, kind)
                .header("X-User-Id", String.valueOf(user)).exchange();
    }

    static String body(MvcTestResult r) {
        try {
            return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    int photoCount() {
        return jdbc.sql("SELECT count(*) FROM reservation_photo").query(Integer.class).single();
    }

    // ---------- 올리기 ----------

    @Test
    @DisplayName("사용 중이면 참여자도 입실 사진을 올리고 201, 같은 예약 사람은 그 사진을 그대로 받는다")
    void uploadAndView() {
        long id = bookAndCheckIn();

        MvcTestResult r = upload(id, 2, "CHECK_IN", JPEG);

        assertThat(r).hasStatus(201).bodyJson().extractingPath("$.kind").isEqualTo("CHECK_IN");
        assertThat(r).bodyJson().extractingPath("$.sizeBytes").isEqualTo(JPEG.length);
        assertThat(r).bodyJson().extractingPath("$.uploadedAt").isEqualTo("2030-03-05T10:40:00+09:00");
        MvcTestResult v = view(id, 1, "CHECK_IN");
        assertThat(v).hasStatusOk().hasContentType("image/jpeg").hasHeader("Cache-Control", "no-store");
        assertThat(v.getResponse().getContentAsByteArray()).isEqualTo(JPEG);
    }

    @Test
    @DisplayName("파일 형식은 앞부분으로 판단: PNG는 image/png로 저장, 이름만 jpg인 글자 파일은 400 PHOTO_INVALID")
    void typeByMagicBytes() {
        long id = bookAndCheckIn();

        assertThat(upload(id, 1, "CHECK_IN", PNG)).hasStatus(201);
        assertThat(view(id, 1, "CHECK_IN")).hasContentType("image/png");
        assertThat(upload(id, 1, "CHECK_OUT", "<script>".getBytes(StandardCharsets.UTF_8)))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("PHOTO_INVALID");
    }

    @Test
    @DisplayName("종류가 없거나 틀리거나 파일이 없으면 400 PHOTO_INVALID")
    void invalidInput() {
        long id = bookAndCheckIn();

        assertThat(upload(id, 1, null, JPEG)).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("PHOTO_INVALID");
        assertThat(upload(id, 1, "SELFIE", JPEG)).hasStatus(400);
        assertThat(upload(id, 1, "CHECK_IN", null)).hasStatus(400);
        assertThat(upload(id, 1, "CHECK_IN", new byte[0])).hasStatus(400);
        assertThat(photoCount()).isZero();
    }

    @Test
    @DisplayName("2MB를 넘으면 400 PHOTO_TOO_LARGE, 딱 2MB는 받는다")
    void tooLarge() {
        long id = bookAndCheckIn();
        byte[] max = Arrays.copyOf(JPEG, 2 * 1024 * 1024);
        byte[] over = Arrays.copyOf(JPEG, 2 * 1024 * 1024 + 1);

        assertThat(upload(id, 1, "CHECK_IN", over)).hasStatus(400).bodyJson().extractingPath("$.code")
                .isEqualTo("PHOTO_TOO_LARGE");
        assertThat(upload(id, 1, "CHECK_IN", max)).hasStatus(201);
    }

    @Test
    @DisplayName("남의 예약이나 없는 예약에 올리면 404 RESERVATION_NOT_FOUND (관리자도 올리지는 못함)")
    void notMember() {
        long id = bookAndCheckIn();

        assertThat(upload(id, 4, "CHECK_IN", JPEG)).hasStatus(404).bodyJson().extractingPath("$.code")
                .isEqualTo("RESERVATION_NOT_FOUND");
        assertThat(upload(id, 5, "CHECK_IN", JPEG)).hasStatus(404);
        assertThat(upload(999, 1, "CHECK_IN", JPEG)).hasStatus(404);
    }

    @Test
    @DisplayName("입실 전(BOOKED)에는 둘 다 409 PHOTO_NOT_ALLOWED_NOW")
    void beforeCheckIn() {
        long id = book();

        assertThat(upload(id, 1, "CHECK_IN", JPEG)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("PHOTO_NOT_ALLOWED_NOW");
        assertThat(upload(id, 1, "CHECK_OUT", JPEG)).hasStatus(409);
    }

    @Test
    @DisplayName("같은 종류 두 번째는 409 PHOTO_ALREADY_UPLOADED, 먼저 올린 사진이 남는다")
    void onlyFirst() {
        long id = bookAndCheckIn();

        assertThat(upload(id, 1, "CHECK_IN", JPEG)).hasStatus(201);
        assertThat(upload(id, 2, "CHECK_IN", PNG)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("PHOTO_ALREADY_UPLOADED");
        assertThat(view(id, 1, "CHECK_IN")).hasContentType("image/jpeg");
        assertThat(upload(id, 2, "CHECK_OUT", PNG)).hasStatus(201);
    }

    @Test
    @DisplayName("퇴실 사진은 퇴실 뒤 30분까지 받고, 31분이면 409. 입실 사진은 퇴실 뒤엔 안 받는다")
    void checkoutGrace() {
        long id = bookAndCheckIn();
        checkedOutAt(id, "11:00");

        at("11:30");
        assertThat(upload(id, 1, "CHECK_IN", JPEG)).hasStatus(409);
        assertThat(upload(id, 1, "CHECK_OUT", JPEG)).hasStatus(201);

        jdbc.sql("DELETE FROM reservation_photo").update();
        at("11:31");
        assertThat(upload(id, 1, "CHECK_OUT", JPEG)).hasStatus(409).bodyJson().extractingPath("$.code")
                .isEqualTo("PHOTO_NOT_ALLOWED_NOW");
    }

    @Test
    @DisplayName("노쇼로 끝난 예약은 퇴실 사진도 안 받는다 (방을 쓰지 않음)")
    void noShow() {
        long id = book();
        jdbc.sql("UPDATE reservation SET status = 'NO_SHOW' WHERE id = :id").param("id", id).update();

        assertThat(upload(id, 1, "CHECK_OUT", JPEG)).hasStatus(409);
    }

    // ---------- 보기 ----------

    @Test
    @DisplayName("관리자는 남의 예약 사진도 보고(신고 판정), 남은 404 PHOTO_NOT_FOUND")
    void viewPermission() {
        long id = bookAndCheckIn();
        assertThat(upload(id, 1, "CHECK_IN", JPEG)).hasStatus(201);

        assertThat(view(id, 5, "CHECK_IN")).hasStatusOk();
        assertThat(view(id, 4, "CHECK_IN")).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("PHOTO_NOT_FOUND");
        assertThat(view(id, 1, "CHECK_OUT")).hasStatus(404);
        assertThat(view(id, 1, "nope")).hasStatus(404);
    }

    // ---------- 지우기 ----------

    @Test
    @DisplayName("올린 지 30일 지난 사진만 지운다")
    void retention() {
        long id = bookAndCheckIn();
        assertThat(upload(id, 1, "CHECK_IN", JPEG)).hasStatus(201);   // 3/5 10:40
        at("11:00");
        assertThat(upload(id, 1, "CHECK_OUT", JPEG)).hasStatus(201);  // 3/5 11:00

        clock.now = OffsetDateTime.parse("2030-04-04T10:50:00+09:00").toInstant();  // 30일 + 10분
        assertThat(photos.deleteExpired()).isEqualTo(1);
        assertThat(view(id, 1, "CHECK_IN")).hasStatus(404);
        assertThat(view(id, 1, "CHECK_OUT")).hasStatusOk();
    }
}
