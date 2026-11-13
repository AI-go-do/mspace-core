package kr.ac.chosun.mspace.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AI 수습사원 카드 점수 GET /api/admin/ai-scores (11/13 지영). 회원 1 학생, 2 관리자. 시각은 지금 기준 몇 분 전 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AiScoresApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    int seq;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE users, ops_action, sensor_event RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 2; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "score" + i + "@chosun.ac.kr").param("s", "2033000" + i).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = 2").update();
    }

    void action(String kind, int room, int agoMin) {
        jdbc.sql("""
                INSERT INTO ops_action (kind, room_id, started_at, detected_at, detail)
                VALUES (:k, :room, now() - make_interval(mins => :ago + 10), now() - make_interval(mins => :ago), '시험')
                """).param("k", kind).param("room", room).param("ago", agoMin).update();
    }

    void co2(int room, int ppm, int agoMin) {
        jdbc.sql("""
                INSERT INTO sensor_event (event_id, room_id, device_id, kind, ppm, observed_at, received_at)
                VALUES (:id, :room, 'co2', 'CO2', :ppm, now() - make_interval(mins => :ago), now() - make_interval(mins => :ago))
                """).param("id", "s-" + (++seq)).param("room", room).param("ppm", ppm).param("ago", agoMin).update();
    }

    void presence(int room, boolean occupied, int agoMin) {
        jdbc.sql("""
                INSERT INTO sensor_event (event_id, room_id, device_id, kind, occupied, observed_at, received_at)
                VALUES (:id, :room, 'radar', 'OCCUPANCY', :o, now() - make_interval(mins => :ago), now() - make_interval(mins => :ago))
                """).param("id", "s-" + (++seq)).param("room", room).param("o", occupied).param("ago", agoMin).update();
    }

    JsonNode item(JsonNode body, String kind) {
        for (JsonNode i : body.path("items")) {
            if (i.path("kind").asString().equals(kind)) {
                return i;
            }
        }
        throw new AssertionError(kind + " 없음");
    }

    @Test
    @DisplayName("측정값에서 시작해 실제 처리를 맞으면 맞힘·전체가, 틀리면 전체만 오르고, 결과 볼 시간이 안 된 건 기다림으로 센다")
    void baseplusLive() throws Exception {
        // 미리 환기: 20분 전 알림 → 15분 전 960(맞음) / 20분 전 알림, 오르지 않음(틀림) / 2분 전 알림(기다림)
        action("VENT_SOON", 1, 20);
        co2(1, 960, 15);
        action("VENT_SOON", 2, 20);
        co2(2, 900, 15);
        action("VENT_SOON", 3, 2);
        // 센서 고장: 30분 전 기록, 그 뒤 값 없음(맞음)
        action("SENSOR_DOWN", 4, 30);
        // 예약 없는 사용: 마지막 재실 "있음"(맞음) / 기록 3분 뒤 "없음"(틀림)
        action("UNBOOKED", 1, 30);
        presence(1, true, 40);
        action("UNBOOKED", 2, 30);
        presence(2, false, 27);

        var r = mvc.get().uri("/api/admin/ai-scores").header("X-User-Id", "2").exchange();

        assertThat(r).hasStatusOk();
        JsonNode body = JsonMapper.builder().build().readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode vent = item(body, "VENT_SOON");
        assertThat(vent.path("live").path("right").asInt()).isEqualTo(1);
        assertThat(vent.path("live").path("total").asInt()).isEqualTo(2);
        assertThat(vent.path("live").path("pending").asInt()).isEqualTo(1);
        assertThat(vent.path("right").asInt()).isEqualTo(96 + 1);
        assertThat(vent.path("total").asInt()).isEqualTo(99 + 2);
        assertThat(item(body, "SENSOR_DOWN").path("live").path("right").asInt()).isEqualTo(1);
        JsonNode unbooked = item(body, "UNBOOKED");
        assertThat(unbooked.path("live").path("right").asInt()).isEqualTo(1);
        assertThat(unbooked.path("live").path("total").asInt()).isEqualTo(2);
        JsonNode noShow = item(body, "NO_SHOW_EXCUSE");
        assertThat(noShow.path("right").asInt()).isEqualTo(19);
        assertThat(noShow.path("total").asInt()).isEqualTo(21);
        assertThat(item(body, "REPORT_JUDGE").path("total").asInt()).as("실제 신고 판정 없음 → 측정값만").isEqualTo(90);
        assertThat(item(body, "APPEAL_REVIEW").path("total").asInt()).isEqualTo(111);
        assertThat(body.path("items")).hasSize(8);
    }

    @Test
    @DisplayName("관리자가 아니면 403")
    void adminOnly() {
        assertThat(mvc.get().uri("/api/admin/ai-scores").header("X-User-Id", "1").exchange()).hasStatus(403);
    }
}
