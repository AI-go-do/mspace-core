package kr.ac.chosun.mspace.sensor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationChannel;
import kr.ac.chosun.mspace.notification.application.SendRemindersUseCase;
import kr.ac.chosun.mspace.reservation.application.SweepReservationsUseCase;
import kr.ac.chosun.mspace.reservation.application.SweepReservationsUseCase.Swept;
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
 * 재실·CO2 센서 (FR-DEV-101~104). 형식은 mspace-device docs/sensor-events-api.md.
 * 기본 예약: 1번 방 10:30–12:30, 대표자 1번 + 참여자 2·3번, 10:30 입실. 시계는 2030년 (다른 예약 테스트와 같은 이유).
 * 센서 신호는 테스트가 시계를 옮기며 직접 보내고, 정리·알림도 직접 부른다 (1분 스케줄러는 테스트에서 꺼 둠).
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token",
        "mspace.sensor.away.still-here-enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SensorEventApiTest.TestBeans.class})
class SensorEventApiTest {

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

    static class RecordingChannel implements NotificationChannel {
        final List<Notification> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(Notification n) {
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
    SweepReservationsUseCase sweeps;

    @Autowired
    SendRemindersUseCase reminders;

    String day = "03-05";
    int eventSeq;

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, access_event, user_suspension, notification_log, "
                + "notification, sensor_event, room_presence RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 6; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
        channel.sent.clear();
    }

    /** 그날 서울 시각. 초까지 쓸 수 있다 ("10:40" 또는 "10:40:30") */
    String iso(String time) {
        return "2030-" + day + "T" + (time.length() == 5 ? time + ":00" : time) + "+09:00";
    }

    void at(String time) {
        clock.now = OffsetDateTime.parse(iso(time)).toInstant();
    }

    /** 대표자 1번(참여자 2·3번)이 1번 방을 잡고 시작 시각에 입실한 것으로 둔다 */
    long bookAndCheckIn(String start, String end) {
        at("10:00");
        MvcTestResult r = mvc.post().uri("/api/reservations").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"%s","endAt":"%s","participantStudentIds":["20230002","20230003"]}"""
                        .formatted(iso(start), iso(end)))
                .exchange();
        assertThat(r).hasStatus(201);
        long id = ((Number) JsonPath.read(body(r), "$.id")).longValue();
        jdbc.sql("UPDATE reservation SET status = 'IN_USE', checked_in_at = start_at WHERE id = :id").param("id", id).update();
        return id;
    }

    static String body(MvcTestResult r) {
        try {
            return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    MvcTestResult post(String token, String json) {
        var req = mvc.post().uri("/api/sensor-events").contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        return req.exchange();
    }

    /** 시계를 time으로 옮기고, 그 시각에 본 재실 값을 새 eventId로 보낸다 */
    String occupancy(String time, boolean occupied) {
        at(time);
        return occupancyObserved(time, occupied);
    }

    /** 시계는 그대로 두고 observed 시각에 본 재실 값을 보낸다 (늦게 온 이벤트) */
    String occupancyObserved(String observed, boolean occupied) {
        MvcTestResult r = post("test-device-token", """
                {"eventId":"o-%d","roomId":1,"deviceId":"sensor-1","kind":"OCCUPANCY","occupied":%s,
                 "sources":{"pir":%s,"radar":false},"observedAt":"%s"}"""
                .formatted(++eventSeq, occupied, occupied, iso(observed)));
        assertThat(r).hasStatusOk();
        return JsonPath.read(body(r), "$.result");
    }

    /** 시계를 time으로 옮기고 CO2 값을 보낸다 */
    String co2(String time, int ppm) {
        at(time);
        MvcTestResult r = post("test-device-token", """
                {"eventId":"c-%d","roomId":1,"deviceId":"sensor-1","kind":"CO2","ppm":%d,"source":"virtual",
                 "observedAt":"%s"}""".formatted(++eventSeq, ppm, iso(time)));
        assertThat(r).hasStatusOk();
        return JsonPath.read(body(r), "$.result");
    }

    /** 시각 from부터 to까지 10초 대신 1분마다 "그대로" 신호 (끊김 1분 기준을 넘지 않게) */
    void heartbeat(String from, String to, boolean occupied) {
        for (int m = minutes(from); m <= minutes(to); m++) {
            occupancy("%02d:%02d".formatted(m / 60, m % 60), occupied);
        }
    }

    static int minutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    String status(long id) {
        return jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", id).query(String.class).single();
    }

    List<String> kindsSentTo(long userId) {
        return jdbc.sql("SELECT kind FROM notification WHERE user_id = :u ORDER BY id").param("u", userId)
                .query(String.class).list();
    }

    int storedEvents(String kind) {
        return jdbc.sql("SELECT count(*) FROM sensor_event WHERE kind = :k").param("k", kind).query(Integer.class).single();
    }

    // ---------- 받기 ----------

    @Test
    @DisplayName("장치 토큰이 없거나 틀리면 401 DEVICE_UNAUTHORIZED")
    void deviceToken() {
        at("10:00");
        String json = """
                {"eventId":"x","roomId":1,"deviceId":"sensor-1","kind":"CO2","ppm":500,"observedAt":"%s"}"""
                .formatted(iso("10:00"));
        assertThat(post(null, json)).hasStatus(401).bodyJson().extractingPath("$.code").isEqualTo("DEVICE_UNAUTHORIZED");
        assertThat(post("wrong", json)).hasStatus(401);
        assertThat(storedEvents("CO2")).isZero();
    }

    @Test
    @DisplayName("형식이 틀리면 400 INVALID_REQUEST: 모르는 kind, 값 빠짐, ppm 범위 밖, 오프셋 없는 시각, 5분 넘는 미래, 없는 방")
    void invalid() {
        at("10:00");
        String now = iso("10:00");
        List<String> bad = List.of(
                """
                {"eventId":"a","roomId":1,"deviceId":"s","kind":"TEMP","ppm":500,"observedAt":"%s"}""".formatted(now),
                """
                {"eventId":"b","roomId":1,"deviceId":"s","kind":"OCCUPANCY","observedAt":"%s"}""".formatted(now),
                """
                {"eventId":"c","roomId":1,"deviceId":"s","kind":"CO2","ppm":10001,"observedAt":"%s"}""".formatted(now),
                """
                {"eventId":"d","roomId":1,"deviceId":"s","kind":"CO2","ppm":500,"observedAt":"2030-03-05T10:00:00"}""",
                """
                {"eventId":"e","roomId":1,"deviceId":"s","kind":"CO2","ppm":500,"observedAt":"%s"}""".formatted(iso("10:05:01")),
                """
                {"eventId":"f","roomId":99,"deviceId":"s","kind":"CO2","ppm":500,"observedAt":"%s"}""".formatted(now),
                """
                {"roomId":1,"deviceId":"s","kind":"CO2","ppm":500,"observedAt":"%s"}""".formatted(now));
        for (String json : bad) {
            assertThat(post("test-device-token", json)).as(json).hasStatus(400)
                    .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        }
        assertThat(storedEvents("CO2")).isZero();
    }

    @Test
    @DisplayName("같은 eventId를 다시 보내면 DUPLICATE, 한 번만 저장")
    void duplicate() {
        at("10:00");
        String json = """
                {"eventId":"same","roomId":1,"deviceId":"sensor-1","kind":"CO2","ppm":700,"observedAt":"%s"}"""
                .formatted(iso("10:00"));
        assertThat(post("test-device-token", json)).bodyJson().extractingPath("$.result").isEqualTo("STORED");
        assertThat(post("test-device-token", json)).bodyJson().extractingPath("$.result").isEqualTo("DUPLICATE");
        assertThat(storedEvents("CO2")).isEqualTo(1);
    }

    @Test
    @DisplayName("10초 '그대로' 신호는 저장하지 않고, 상태가 바뀐 이벤트만 남긴다 (센서별 값 sources도 같이)")
    void onlyChangesAreStored() {
        heartbeat("10:00", "10:05", true);   // 처음 1개만
        occupancy("10:06", false);             // 바뀜
        heartbeat("10:07", "10:09", false);

        assertThat(storedEvents("OCCUPANCY")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT sources->>'pir' FROM sensor_event ORDER BY id DESC LIMIT 1").query(String.class).single())
                .isEqualTo("false");
        assertThat(jdbc.sql("SELECT occupied FROM room_presence WHERE room_id = 1").query(Boolean.class).single()).isFalse();
    }

    // ---------- 자리 비움 (FR-DEV-103) ----------

    @Test
    @DisplayName("수용 기준: 사용 중 10분 비어 있으면 대표자에게만 알림, 15분이면 AWAY_RELEASED로 퇴실·방이 풀린다")
    void awayNotifyThenRelease() {
        long id = bookAndCheckIn("10:30", "12:30");
        occupancy("10:35", false);
        heartbeat("10:36", "10:44", false);
        reminders.sendDue();
        assertThat(kindsSentTo(1)).doesNotContain("AWAY_SOON");   // 9분

        heartbeat("10:45", "10:45", false);
        reminders.sendDue();
        reminders.sendDue();   // 두 번 돌아도 한 번만
        assertThat(kindsSentTo(1)).containsOnlyOnce("AWAY_SOON");
        assertThat(kindsSentTo(2)).doesNotContain("AWAY_SOON");   // 번호를 가진 대표자만

        heartbeat("10:46", "10:49", false);
        assertThat(sweeps.sweep()).isEqualTo(new Swept(0, 0, 0));   // 14분
        heartbeat("10:50", "10:50", false);
        assertThat(sweeps.sweep()).isEqualTo(new Swept(0, 0, 1));
        assertThat(status(id)).isEqualTo("AWAY_RELEASED");
        assertThat(jdbc.sql("SELECT checked_out_at FROM reservation WHERE id = :id").param("id", id)
                .query(Instant.class).single()).isEqualTo(OffsetDateTime.parse(iso("10:50")).toInstant());
        // 방과 대표자 1건 제한이 풀려 바로 다시 잡을 수 있다 (참여자 시간도 풀림)
        at("10:51");
        assertThat(mvc.post().uri("/api/reservations").header("X-User-Id", "1").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"%s","endAt":"%s","participantStudentIds":["20230002","20230003"]}"""
                        .formatted(iso("11:00"), iso("12:00")))
                .exchange()).hasStatus(201);
    }

    @Test
    @DisplayName("돌아오면 타이머가 처음부터: 10:35 비어 있음 → 10:45 있음 → 10:46 비어 있음이면 11:01에 퇴실")
    void comingBackResetsTimer() {
        long id = bookAndCheckIn("10:30", "12:30");
        occupancy("10:35", false);
        heartbeat("10:36", "10:44", false);
        occupancy("10:45", true);
        heartbeat("10:46", "10:59", false);
        sweeps.sweep();
        assertThat(status(id)).isEqualTo("IN_USE");

        heartbeat("11:00", "11:01", false);
        sweeps.sweep();
        assertThat(status(id)).isEqualTo("AWAY_RELEASED");
    }

    @Test
    @DisplayName("센서가 1분 넘게 끊기면 '있음'으로 보고 퇴실하지 않는다. 다시 오면 그때부터 15분")
    void offlineMeansOccupied() {
        long id = bookAndCheckIn("10:30", "12:30");
        occupancy("10:35", false);   // 그 뒤 끊김
        at("10:55");
        sweeps.sweep();
        reminders.sendDue();
        assertThat(status(id)).isEqualTo("IN_USE");
        assertThat(kindsSentTo(1)).doesNotContain("AWAY_SOON");

        // 끊긴 동안 쌓아 둔 옛 이벤트가 늦게 와도 타이머를 당기지 않는다
        assertThat(occupancyObserved("10:40", false)).isEqualTo("STORED");
        heartbeat("10:56", "11:09", false);
        sweeps.sweep();
        assertThat(status(id)).isEqualTo("IN_USE");   // 10:55부터 14분

        heartbeat("11:10", "11:10", false);
        sweeps.sweep();
        assertThat(status(id)).isEqualTo("AWAY_RELEASED");
    }

    @Test
    @DisplayName("늦게 온 옛 이벤트는 지금 상태를 덮지 않는다 (기록에는 남는다)")
    void lateEventDoesNotOverride() {
        occupancy("10:35", true);
        occupancy("10:36", false);
        assertThat(occupancyObserved("10:35:30", true)).isEqualTo("STORED");

        assertThat(jdbc.sql("SELECT occupied FROM room_presence WHERE room_id = 1").query(Boolean.class).single()).isFalse();
        assertThat(storedEvents("OCCUPANCY")).isEqualTo(3);
    }

    @Test
    @DisplayName("끝 시각이 되면 자리 비움이 아니라 보통 자동 퇴실로 끝난다")
    void endTimeWinsOverAway() {
        long id = bookAndCheckIn("10:30", "11:00");
        occupancy("10:40", false);
        heartbeat("10:41", "11:00", false);
        assertThat(sweeps.sweep()).isEqualTo(new Swept(0, 1, 0));
        assertThat(status(id)).isEqualTo("AUTO_CHECKED_OUT");
    }

    @Test
    @DisplayName("벌칙: 30일 안 노쇼 1번 + 자리 비움 3번(= 노쇼 1번)이면 노쇼 2번과 같이 14일 정지. 자리 비움 2번까지는 정지 없음")
    void awayCountsTowardSuspension() {
        // 노쇼 1번 (03-01)
        day = "03-01";
        at("10:00");
        assertThat(mvc.post().uri("/api/reservations").header("X-User-Id", "1").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"%s","endAt":"%s","participantStudentIds":["20230002","20230003"]}"""
                        .formatted(iso("10:30"), iso("11:30")))
                .exchange()).hasStatus(201);
        at("10:46");
        sweeps.sweep();

        for (String d : List.of("03-02", "03-03", "03-04")) {
            day = d;
            assertThat(suspensions()).isZero();
            long id = bookAndCheckIn("10:30", "12:30");
            occupancy("10:31", false);
            heartbeat("10:32", "10:46", false);
            sweeps.sweep();
            assertThat(status(id)).isEqualTo("AWAY_RELEASED");
        }
        assertThat(suspensions()).isEqualTo(1);
    }

    MvcTestResult stillHere(long id, long userId) {
        return mvc.post().uri("/api/reservations/" + id + "/still-here").header("X-User-Id", String.valueOf(userId)).exchange();
    }

    @Test
    @DisplayName("아직 있어요: 누르면 타이머가 누른 시각부터 다시. 그 뒤엔 10분 비면 알림 없이 바로 퇴실 (11/5 지영)")
    void stillHereThenReleaseAfterTen() {
        long id = bookAndCheckIn("10:30", "12:30");
        occupancy("10:35", false);
        heartbeat("10:36", "10:45", false);
        reminders.sendDue();
        assertThat(kindsSentTo(1)).containsExactly("AWAY_SOON");
        assertThat(jdbc.sql("SELECT body FROM notification WHERE user_id = 1").query(String.class).single())
                .contains("아직 있어요");

        at("10:47");
        assertThat(stillHere(id, 1)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("IN_USE");
        heartbeat("10:47", "10:56", false);
        reminders.sendDue();
        sweeps.sweep();
        assertThat(status(id)).isEqualTo("IN_USE");   // 10:50(원래 15분)이 지나도 그대로, 10:47부터 9분

        heartbeat("10:57", "10:57", false);
        reminders.sendDue();
        sweeps.sweep();
        assertThat(status(id)).isEqualTo("AWAY_RELEASED");   // 누른 뒤 10분
        assertThat(kindsSentTo(1)).containsExactly("AWAY_SOON");   // 두 번째 알림 없음
        assertThat(jdbc.sql("SELECT checked_out_at FROM reservation WHERE id = :id").param("id", id)
                .query(Instant.class).single()).isEqualTo(OffsetDateTime.parse(iso("10:57")).toInstant());
    }

    @Test
    @DisplayName("아직 있어요 뒤에 돌아왔다 다시 나가면 그때부터 10분")
    void stillHereThenBackThenAway() {
        long id = bookAndCheckIn("10:30", "12:30");
        at("10:40");
        assertThat(stillHere(id, 1)).hasStatusOk();
        occupancy("10:41", true);
        occupancy("10:50", false);
        heartbeat("10:51", "10:59", false);
        sweeps.sweep();
        assertThat(status(id)).isEqualTo("IN_USE");   // 10:50부터 9분
        heartbeat("11:00", "11:00", false);
        sweeps.sweep();
        assertThat(status(id)).isEqualTo("AWAY_RELEASED");
    }

    @Test
    @DisplayName("아직 있어요: 예약당 1번(두 번째 409), 대표자만(참여자 403·남 404), 사용 중일 때만(409)")
    void stillHereRules() {
        long id = bookAndCheckIn("10:30", "12:30");
        at("10:40");
        assertThat(stillHere(id, 2)).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("NOT_RESERVATION_OWNER");
        assertThat(stillHere(id, 4)).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_FOUND");
        assertThat(stillHere(id, 1)).hasStatusOk();
        assertThat(stillHere(id, 1)).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("STILL_HERE_ALREADY_USED");

        assertThat(mvc.post().uri("/api/reservations/" + id + "/checkout").header("X-User-Id", "1").exchange()).hasStatusOk();
        assertThat(stillHere(id, 1)).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("RESERVATION_NOT_IN_USE");
    }

    int suspensions() {
        return jdbc.sql("SELECT count(*) FROM user_suspension WHERE user_id = 1").query(Integer.class).single();
    }

    // ---------- 환기 (FR-DEV-104) ----------

    @Test
    @DisplayName("수용 기준: 1000ppm 초과가 1분 간격으로 3번이면 그 방 사용자 전원에게 환기 알림, 같은 방 30분에 한 번")
    void ventilate() {
        bookAndCheckIn("10:30", "12:30");
        co2("10:40", 1100);
        co2("10:41", 1200);
        assertThat(kindsSentTo(1)).isEmpty();   // 2번째
        co2("10:42", 1150);
        for (long u = 1; u <= 3; u++) {
            assertThat(kindsSentTo(u)).containsExactly("VENTILATE");
        }
        assertThat(channel.sent).hasSize(3);   // 커밋 뒤 바깥 채널로도

        co2("10:43", 1300);
        co2("11:11", 1300);
        co2("11:12", 1300);   // 11:11–11:12만 이어짐 (10:43과는 28분 떨어짐)
        assertThat(kindsSentTo(1)).hasSize(1);
        co2("11:13", 1300);   // 11:11·11:12·11:13 3번 연속, 지난 알림(10:42)에서 31분
        assertThat(kindsSentTo(1)).containsExactly("VENTILATE", "VENTILATE");
        assertThat(storedEvents("CO2")).isEqualTo(7);
    }

    @Test
    @DisplayName("잠깐 튀거나 너무 촘촘한 값은 무시: 중간에 1000 이하, 30초 간격 3개는 알림 없음")
    void ventilateNeedsRealStreak() {
        bookAndCheckIn("10:30", "12:30");
        co2("10:40", 1100);
        co2("10:41", 900);
        co2("10:42", 1100);
        co2("10:43", 1100);   // 900 다음부터 2번뿐
        co2("10:50:00", 1200);
        co2("10:50:20", 1200);
        co2("10:50:40", 1200);   // 간격 20초
        assertThat(kindsSentTo(1)).isEmpty();
    }

    @Test
    @DisplayName("방을 쓰는 사람이 없으면 환기 알림을 보내지 않는다")
    void ventilateOnlyWhenInUse() {
        co2("10:40", 1100);
        co2("10:41", 1100);
        co2("10:42", 1100);
        assertThat(jdbc.sql("SELECT count(*) FROM notification").query(Integer.class).single()).isZero();
    }
}
