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

/** 관리자 "AI가 처리한 일" 목록 GET /api/admin/ops-actions (11/12 지영). 회원 1 학생, 2 관리자 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpsActionsApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE users, ops_action RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 2; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "opsapi" + i + "@chosun.ac.kr").param("s", "2032000" + i).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = 2").update();
    }

    void action(String kind, int room, String detectedAt, String detail) {
        jdbc.sql("""
                INSERT INTO ops_action (kind, room_id, started_at, detected_at, detail)
                VALUES (:k, :room, CAST(:at AS timestamptz) - interval '10 minutes', CAST(:at AS timestamptz), :d)
                """).param("k", kind).param("room", room).param("at", detectedAt).param("d", detail).update();
    }

    @Test
    @DisplayName("그날(서울) 처리한 일을 최근 것부터 돌려주고, 다른 날 것은 빼고, 관리자가 아니면 403")
    void listsDayNewestFirst() throws Exception {
        action("SENSOR_DOWN", 1, "2030-03-05T09:00:00+09:00", "마지막 신호 6분 전, 다른 방은 신호 있음");
        action("UNBOOKED", 2, "2030-03-05T10:00:00+09:00", "예약 없이 12분째 사람 있음");
        action("UNBOOKED", 2, "2030-03-04T23:59:00+09:00", "어제");

        var r = mvc.get().uri("/api/admin/ops-actions?date=2030-03-05").header("X-User-Id", "2").exchange();

        assertThat(r).hasStatusOk();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"kind\":\"UNBOOKED\"", "\"roomName\":\"2번 방\"", "예약 없이 12분째 사람 있음",
                "\"detectedAt\":\"2030-03-05T10:00:00+09:00\"", "\"notified\":0").doesNotContain("어제");
        assertThat(body.indexOf("UNBOOKED")).isLessThan(body.indexOf("SENSOR_DOWN"));
        assertThat(mvc.get().uri("/api/admin/ops-actions").header("X-User-Id", "1").exchange()).hasStatus(403);
    }

    @Test
    @DisplayName("같은 종류·방·시작 시각은 한 번만 남는다 (서버 두 대가 같이 봐도)")
    void onceByUniqueKey() {
        action("SENSOR_DOWN", 1, "2030-03-05T09:00:00+09:00", "첫 번");

        int again = jdbc.sql("""
                INSERT INTO ops_action (kind, room_id, started_at, detected_at, detail)
                VALUES ('SENSOR_DOWN', 1, CAST('2030-03-05T08:50:00+09:00' AS timestamptz), now(), '두 번')
                ON CONFLICT ON CONSTRAINT ops_action_once DO NOTHING
                """).update();

        assertThat(again).isZero();
    }
}
