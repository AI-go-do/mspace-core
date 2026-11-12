package kr.ac.chosun.mspace.modelscore;

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

/** 방 카메라 모델 시험 점수 (11/12 재웅·지영): 젯슨이 채점 숫자를 올리고 관리자가 최신 순으로 본다 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true",
        "mspace.access.devices=camera-1:1:room1-camera,camera-2:2:room2-camera"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ModelScoreApiTest {

    static final String CAMERA1 = "Bearer room1-camera";
    static final long STUDENT = 1;
    static final long ADMIN = 2;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE model_score, users RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 2; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "m" + i + "@chosun.ac.kr").param("s", "202600" + (40 + i)).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
    }

    static String score(String eventId, String model, double accuracy, String measuredAt) {
        return """
                {"eventId":"%s","kind":"TIDY","model":"%s","accuracy":%s,"accuracy3":0.574,"cases":47,
                 "serious":2,"medianSeconds":3.0,"measuredAt":"%s"}"""
                .formatted(eventId, model, accuracy, measuredAt);
    }

    MvcTestResult post(int roomId, String token, String json) {
        var req = mvc.post().uri("/api/rooms/{id}/model-scores", roomId).contentType(MediaType.APPLICATION_JSON)
                .content(json);
        return token == null ? req.exchange() : req.header("Authorization", token).exchange();
    }

    MvcTestResult list(long userId) {
        return mvc.get().uri("/api/admin/model-scores?kind=TIDY").header("X-User-Id", String.valueOf(userId))
                .exchange();
    }

    long count() {
        return jdbc.sql("SELECT count(*) FROM model_score").query((rs, n) -> rs.getLong(1)).single();
    }

    @Test
    @DisplayName("젯슨이 채점 숫자를 보내면 저장하고, 방·받은 시각을 같이 남긴다")
    void stores() {
        // when
        MvcTestResult r = post(1, CAMERA1, score("ms-1", "yolov8s", 0.766, "2026-11-11T21:00:00+09:00"));

        // then
        assertThat(r).hasStatusOk().bodyJson().extractingPath("$.result").isEqualTo("STORED");
        assertThat(jdbc.sql("SELECT room_id, model, cases FROM model_score").query().singleRow())
                .containsEntry("room_id", 1).containsEntry("model", "yolov8s").containsEntry("cases", 47);
    }

    @Test
    @DisplayName("같은 eventId를 두 번 보내면 한 번만 저장하고 두 번째는 DUPLICATE")
    void duplicate() {
        // given
        assertThat(post(1, CAMERA1, score("ms-d", "yolov8s", 0.766, "2026-11-11T21:00:00+09:00"))).hasStatusOk();

        // when
        MvcTestResult r = post(1, CAMERA1, score("ms-d", "yolov8s", 0.87, "2026-11-12T21:00:00+09:00"));

        // then
        assertThat(r).hasStatusOk().bodyJson().extractingPath("$.result").isEqualTo("DUPLICATE");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("장치 토큰이 없으면 401, 다른 방 카메라 토큰이면 403 DEVICE_ROOM_MISMATCH")
    void deviceToken() {
        String body = score("ms-t", "yolov8s", 0.766, "2026-11-11T21:00:00+09:00");

        assertThat(post(1, null, body)).hasStatus(401);
        assertThat(post(1, "Bearer room2-camera", body)).hasStatus(403)
                .bodyJson().extractingPath("$.code").isEqualTo("DEVICE_ROOM_MISMATCH");
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("형식이 틀리면 400: 정확도 1 초과(퍼센트로 보냄), cases 0, kind 오타, 오프셋 없는 시각")
    void invalid() {
        assertThat(post(1, CAMERA1, score("ms-x1", "yolov8s", 76.6, "2026-11-11T21:00:00+09:00")))
                .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(post(1, CAMERA1, score("ms-x2", "yolov8s", 0.7, "2026-11-11T21:00:00+09:00")
                .replace("\"cases\":47", "\"cases\":0"))).hasStatus(400);
        assertThat(post(1, CAMERA1, score("ms-x3", "yolov8s", 0.7, "2026-11-11T21:00:00+09:00")
                .replace("TIDY", "tidy"))).hasStatus(400);
        assertThat(post(1, CAMERA1, score("ms-x4", "yolov8s", 0.7, "2026-11-11T21:00:00"))).hasStatus(400);
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("관리자 목록: 채점 시각 최근 순, 세 가지 정확도·심각 오류도 같이 준다. 보낸 순서와 상관없다")
    void adminListNewestFirst() {
        // given: 87% 판을 먼저 받고, 그보다 앞서 채점한 76.6% 판을 나중에 받았다
        assertThat(post(1, CAMERA1, score("ms-b", "yolov8m", 0.87, "2026-11-12T21:00:00+09:00"))).hasStatusOk();
        assertThat(post(1, CAMERA1, score("ms-a", "yolov8s", 0.766, "2026-11-11T21:00:00+09:00"))).hasStatusOk();

        // when·then
        assertThat(list(ADMIN)).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.items.length()").isEqualTo(2);
            j.assertThat().extractingPath("$.items[0].model").isEqualTo("yolov8m");
            j.assertThat().extractingPath("$.items[0].accuracy").isEqualTo(0.87);
            j.assertThat().extractingPath("$.items[0].measuredAt").isEqualTo("2026-11-12T21:00:00+09:00");
            j.assertThat().extractingPath("$.items[1].kind").isEqualTo("TIDY");
            j.assertThat().extractingPath("$.items[1].accuracy").isEqualTo(0.766);
            j.assertThat().extractingPath("$.items[1].accuracy3").isEqualTo(0.574);
            j.assertThat().extractingPath("$.items[1].cases").isEqualTo(47);
            j.assertThat().extractingPath("$.items[1].serious").isEqualTo(2);
            j.assertThat().extractingPath("$.items[1].medianSeconds").isEqualTo(3.0);
        });
    }

    @Test
    @DisplayName("관리자 목록: 학생은 403 ADMIN_ONLY, 아직 없으면 빈 목록")
    void adminGuards() {
        assertThat(list(STUDENT)).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("ADMIN_ONLY");
        assertThat(list(ADMIN)).hasStatusOk().bodyJson().extractingPath("$.items.length()").isEqualTo(0);
    }
}
