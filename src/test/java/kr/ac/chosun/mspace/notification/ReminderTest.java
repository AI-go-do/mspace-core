package kr.ac.chosun.mspace.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationChannel;
import kr.ac.chosun.mspace.notification.application.SendRemindersUseCase;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 예약 알림 3종 (11/5 지영): 입실 10분 전(FR-NTF-101), 종료 10분 전(FR-NTF-102), 노쇼 5분 전(FR-NTF-104).
 * 예약·사람·종류별 한 번만(NFR-NTF-101). 기본 예약은 1번 방 10:30–11:30, 대표자 1번 + 참여자 2·3번 (입실 마감 10:45).
 * 시계는 2030년 (다른 예약 테스트와 같은 이유: 지금 시각과 겹치지 않게).
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReminderTest.TestBeans.class})
class ReminderTest {

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

    /** 메일 어댑터(재웅 담당) 자리에 끼우는 가짜 채널. 2번 회원에게 보낼 때는 일부러 실패한다 */
    static class RecordingChannel implements NotificationChannel {
        final List<Notification> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(Notification n) {
            if (n.userId() == 2) {
                throw new IllegalStateException("메일 서버 응답 없음");
            }
            sent.add(n);
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock();
        }

        @Bean
        RecordingChannel recordingChannel() {
            return new RecordingChannel();
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SettableClock clock;

    @Autowired
    RecordingChannel channel;

    @Autowired
    SendRemindersUseCase reminders;

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, access_event, user_suspension, notification_log, "
                + "notification RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 6; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
        channel.sent.clear();
    }

    void at(String time) {
        clock.now = OffsetDateTime.parse("2030-03-05T" + time + ":00+09:00").toInstant();
    }

    /** 시계를 10:00으로 두고 미리 예약. owner 1이면 2·3번과, 4면 5·6번과 */
    long book(int owner, String start, String end) {
        at("10:00");
        String others = owner == 1 ? "\"20230002\",\"20230003\"" : "\"20230005\",\"20230006\"";
        MvcTestResult r = mvc.post().uri("/api/reservations").header("X-User-Id", String.valueOf(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2030-03-05T%s:00+09:00","endAt":"2030-03-05T%s:00+09:00",
                         "participantStudentIds":[%s]}""".formatted(start, end, others))
                .exchange();
        assertThat(r).hasStatus(201);
        return idOf(r);
    }

    static long idOf(MvcTestResult r) {
        try {
            return ((Number) JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 키패드 입실 대신 상태만 바꾼다 (입실 규칙은 KeypadAndCheckoutApiTest에서) */
    void checkIn(long id) {
        jdbc.sql("UPDATE reservation SET status = 'IN_USE', checked_in_at = start_at WHERE id = :id").param("id", id).update();
    }

    int sendAt(String time) {
        at(time);
        return reminders.sendDue();
    }

    record Row(long userId, long reservationId, String kind, String title, String body) {
    }

    List<Row> inbox() {
        return jdbc.sql("SELECT user_id, reservation_id, kind, title, body FROM notification ORDER BY id")
                .query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
    }

    @Test
    @DisplayName("수용 기준: 시작 10분 전에 대표자·참여자 전원에게 \"입실 10분 전입니다\", 입실 마감 시각 안내")
    void startSoon() {
        long id = book(1, "10:30", "11:30");

        assertThat(sendAt("10:19")).isZero();   // 11분 전은 아직
        assertThat(sendAt("10:20")).isEqualTo(3);

        assertThat(inbox()).extracting(Row::userId).containsExactly(1L, 2L, 3L);
        Row first = inbox().getFirst();
        assertThat(first.reservationId()).isEqualTo(id);
        assertThat(first.kind()).isEqualTo("START_SOON");
        assertThat(first.title()).isEqualTo("입실 10분 전입니다");
        assertThat(first.body()).isEqualTo("1번 방 10:30 시작이에요. 10:45까지 키패드에 번호를 넣지 않으면 노쇼로 예약이 풀려요.");
    }

    @Test
    @DisplayName("수용 기준(NFR-NTF-101): 같은 알림은 예약·사람·종류별 한 번만. 1분마다 다시 돌아도 또 안 간다")
    void onlyOnce() {
        book(1, "10:30", "11:30");

        assertThat(sendAt("10:20")).isEqualTo(3);
        assertThat(sendAt("10:21")).isZero();
        assertThat(sendAt("10:29")).isZero();
        assertThat(inbox()).hasSize(3);
    }

    @Test
    @DisplayName("정리가 늦게 돌아도 시작 전이면 보내고, 시작이 지나면 입실 안내는 보내지 않는다")
    void windowOnly() {
        book(1, "10:30", "11:30");

        assertThat(sendAt("10:31")).isZero();
        assertThat(inbox()).isEmpty();
    }

    @Test
    @DisplayName("수용 기준: 종료 10분 전에 전원에게 \"종료 10분 전입니다\". 다음 칸이 비어 있으면 연장 안내")
    void endSoonWithExtendHint() {
        long id = book(1, "10:30", "11:30");
        checkIn(id);

        assertThat(sendAt("11:19")).isZero();
        assertThat(sendAt("11:20")).isEqualTo(3);
        Row first = inbox().getFirst();
        assertThat(first.kind()).isEqualTo("END_SOON");
        assertThat(first.title()).isEqualTo("종료 10분 전입니다");
        assertThat(first.body()).isEqualTo("1번 방 11:30에 끝나요. 퇴실 전에 자리를 정리해 주세요. 다음 칸이 비어 있어 연장할 수 있어요.");
    }

    @Test
    @DisplayName("다음 칸에 다른 예약이 있으면 연장 안내는 빼고 보낸다")
    void endSoonWithoutExtendHint() {
        long id = book(1, "10:30", "11:30");
        book(4, "11:30", "12:30");
        checkIn(id);

        assertThat(sendAt("11:20")).isEqualTo(6);   // 우리 팀 종료 안내 3 + 다음 팀(4·5·6번) 입실 안내 3
        assertThat(inbox()).filteredOn(r -> r.kind().equals("END_SOON")).extracting(Row::body)
                .hasSize(3).containsOnly("1번 방 11:30에 끝나요. 퇴실 전에 자리를 정리해 주세요.");
    }

    @Test
    @DisplayName("연장하면 새 예약에 종료 10분 전 안내가 한 번 더 간다 (연장은 1회라 연장 안내는 빼고). 원래 예약은 퇴실이라 다시 안 간다")
    void endSoonAgainAfterContinue() {
        // given: 11:20에 종료 안내를 받고 11:25에 12:00까지 연장
        long id = book(1, "10:30", "11:30");
        checkIn(id);
        assertThat(sendAt("11:20")).isEqualTo(3);
        at("11:25");
        MvcTestResult r = mvc.post().uri("/api/reservations/" + id + "/continue").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"endAt":"2030-03-05T12:00:00+09:00"}""")
                .exchange();
        assertThat(r).hasStatus(201);
        long next = idOf(r);

        // when
        assertThat(sendAt("11:49")).isZero();
        int sent = sendAt("11:50");

        // then: 새 예약 전원(3명)에게만, 연장 안내 없이
        assertThat(sent).isEqualTo(3);
        assertThat(inbox()).filteredOn(row -> row.reservationId() == next).extracting(Row::body)
                .hasSize(3).containsOnly("1번 방 12:00에 끝나요. 퇴실 전에 자리를 정리해 주세요.");
        assertThat(inbox()).filteredOn(row -> row.reservationId() == id).hasSize(3);
    }

    @Test
    @DisplayName("수용 기준: 입실하지 않으면 노쇼 5분 전(시작 + 10분)에 대표자에게만 \"노쇼 5분 전입니다\"")
    void noShowSoonOwnerOnly() {
        book(1, "10:30", "11:30");

        assertThat(sendAt("10:39")).isZero();
        assertThat(sendAt("10:40")).isEqualTo(1);
        Row only = inbox().getFirst();
        assertThat(only.userId()).isEqualTo(1L);
        assertThat(only.kind()).isEqualTo("NO_SHOW_SOON");
        assertThat(only.title()).isEqualTo("노쇼 5분 전입니다");
        assertThat(only.body()).isEqualTo("1번 방 10:45까지 키패드에 번호를 넣지 않으면 방이 풀려요.");
    }

    @Test
    @DisplayName("입실했으면 노쇼 직전 안내는 가지 않는다")
    void noShowSoonSkippedAfterCheckIn() {
        long id = book(1, "10:30", "11:30");
        checkIn(id);

        assertThat(sendAt("10:40")).isZero();
    }

    @Test
    @DisplayName("지금 바로 쓰기: 입실 안내는 없고, 누른 시각 + 5분에 노쇼 직전 안내")
    void instantStart() {
        at("10:17");
        MvcTestResult r = mvc.post().uri("/api/reservations/instant").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"endAt":"2030-03-05T11:30:00+09:00","participantStudentIds":["20230002","20230003"]}""")
                .exchange();
        assertThat(r).hasStatus(201);

        assertThat(sendAt("10:18")).isZero();
        assertThat(sendAt("10:22")).isEqualTo(1);   // 마감 10:27의 5분 전
        assertThat(inbox()).extracting(Row::kind).containsExactly("NO_SHOW_SOON");
    }

    @Test
    @DisplayName("취소한 예약에는 알림이 가지 않는다")
    void canceledGetsNothing() {
        long id = book(1, "10:30", "11:30");
        assertThat(mvc.post().uri("/api/reservations/" + id + "/cancel").header("X-User-Id", "1").exchange())
                .hasStatus(200);

        assertThat(sendAt("10:20")).isZero();
        assertThat(sendAt("10:40")).isZero();
    }

    @Test
    @DisplayName("서버 두 대처럼 동시에 10번 돌아도 알림은 3건 (notification_log PK + ON CONFLICT DO NOTHING)")
    void concurrentOnce() throws Exception {
        book(1, "10:30", "11:30");
        at("10:20");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return reminders.sendDue();
            }));
        }
        go.countDown();
        int total = 0;
        for (Future<Integer> f : results) {
            total += f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(total).isEqualTo(3);
        assertThat(inbox()).hasSize(3);
    }

    @Test
    @DisplayName("바깥 채널(메일)이 한 사람에게 실패해도 나머지는 보내고, 알림함에는 셋 다 남는다")
    void channelFailureKeepsInbox() {
        book(1, "10:30", "11:30");

        assertThat(sendAt("10:20")).isEqualTo(3);
        assertThat(channel.sent).extracting(Notification::userId).containsExactly(1L, 3L);
        assertThat(inbox()).hasSize(3);
        assertThat(sendAt("10:21")).isZero();   // 실패한 메일을 다시 보내지는 않는다 (최대 한 번)
    }

    @Test
    @DisplayName("GET /api/notifications/me: 내 알림만 최근 순으로, 안 읽음 표시")
    void myInbox() {
        long id = book(1, "10:30", "11:30");
        sendAt("10:20");
        checkIn(id);
        sendAt("11:20");

        MvcTestResult mine = mvc.get().uri("/api/notifications/me").header("X-User-Id", "2").exchange();
        assertThat(mine).hasStatus(200);
        assertThat(mine).bodyJson().extractingPath("$.length()").isEqualTo(2);
        assertThat(mine).bodyJson().extractingPath("$[0].kind").isEqualTo("END_SOON");
        assertThat(mine).bodyJson().extractingPath("$[1].kind").isEqualTo("START_SOON");
        assertThat(mine).bodyJson().extractingPath("$[0].read").isEqualTo(false);
        assertThat(mine).bodyJson().extractingPath("$[0].createdAt").isEqualTo("2030-03-05T11:20:00+09:00");

        MvcTestResult other = mvc.get().uri("/api/notifications/me").header("X-User-Id", "4").exchange();
        assertThat(other).bodyJson().extractingPath("$.length()").isEqualTo(0);
    }
}
