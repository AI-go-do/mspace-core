package kr.ac.chosun.mspace.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
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

/** 알림 읽음 표시 (FR-NTF-103 "안 읽은 알림은 점 표시") */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationReadTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE notification, users RESTART IDENTITY CASCADE").update();
        jdbc.sql("""
                INSERT INTO users (email, student_id, name, verified_at) VALUES
                  ('u1@chosun.ac.kr', '20230001', '홍길동', now()),
                  ('u2@chosun.ac.kr', '20230002', '이몽룡', now())
                """).update();
        // 1번에게 3개(1, 2, 3), 2번에게 1개(4)
        jdbc.sql("""
                INSERT INTO notification (user_id, kind, title, body) VALUES
                  (1, 'START_SOON', '입실 10분 전입니다', '창작실 1'),
                  (1, 'END_SOON', '종료 10분 전입니다', '창작실 1'),
                  (1, 'NO_SHOW_SOON', '노쇼 5분 전입니다', '창작실 1'),
                  (2, 'START_SOON', '입실 10분 전입니다', '창작실 2')
                """).update();
    }

    MvcTestResult read(long who, long id) {
        return mvc.post().uri("/api/notifications/{id}/read", id).header("X-User-Id", String.valueOf(who)).exchange();
    }

    MvcTestResult unread(long who) {
        return mvc.get().uri("/api/notifications/me/unread-count").header("X-User-Id", String.valueOf(who)).exchange();
    }

    Timestamp readAt(long id) {
        return jdbc.sql("SELECT read_at FROM notification WHERE id = :id").param("id", id)
                .query(Timestamp.class).optional().orElse(null);
    }

    @Test
    @DisplayName("수용 기준: 알림 하나를 읽으면 목록에 read=true, 안 읽은 개수가 1 줄어든다")
    void readOne() {
        assertThat(unread(1)).hasStatusOk().bodyJson().extractingPath("$.count").isEqualTo(3);

        assertThat(read(1, 2)).hasStatus(204);

        assertThat(unread(1)).bodyJson().extractingPath("$.count").isEqualTo(2);
        assertThat(mvc.get().uri("/api/notifications/me").header("X-User-Id", "1").exchange())
                .bodyJson().extractingPath("$[?(@.id == 2)].read").asArray().containsExactly(true);
    }

    @Test
    @DisplayName("같은 알림을 두 번 읽어도 204, 처음 읽은 시각은 그대로")
    void readTwiceKeepsFirstTime() {
        assertThat(read(1, 1)).hasStatus(204);
        assertThat(readAt(1)).isNotNull();
        // 처음 읽은 시각을 과거로 옮겨 두면, 두 번째 요청이 덮어쓰는지 확실히 드러난다
        Timestamp first = Timestamp.valueOf("2026-11-01 09:00:00");
        jdbc.sql("UPDATE notification SET read_at = :t WHERE id = 1").param("t", first).update();

        assertThat(read(1, 1)).hasStatus(204);

        assertThat(readAt(1)).isEqualTo(first);
    }

    @Test
    @DisplayName("남의 알림이나 없는 알림은 404 NOTIFICATION_NOT_FOUND. 남의 알림은 그대로 안 읽음")
    void othersNotification() {
        assertThat(read(1, 4)).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("NOTIFICATION_NOT_FOUND");
        assertThat(read(1, 999)).hasStatus(404);

        assertThat(readAt(4)).isNull();
    }

    @Test
    @DisplayName("모두 읽음: 내 안 읽은 알림만 바뀐 개수를 돌려주고, 남의 알림은 그대로")
    void readAll() {
        read(1, 1);

        assertThat(mvc.post().uri("/api/notifications/read-all").header("X-User-Id", "1").exchange())
                .hasStatusOk().bodyJson().extractingPath("$.count").isEqualTo(2);

        assertThat(unread(1)).bodyJson().extractingPath("$.count").isEqualTo(0);
        assertThat(unread(2)).bodyJson().extractingPath("$.count").isEqualTo(1);
    }

    @Test
    @DisplayName("로그인 안 하면 401")
    void needsLogin() {
        assertThat(mvc.post().uri("/api/notifications/1/read").exchange()).hasStatus(401);
        assertThat(mvc.get().uri("/api/notifications/me/unread-count").exchange()).hasStatus(401);
    }
}
