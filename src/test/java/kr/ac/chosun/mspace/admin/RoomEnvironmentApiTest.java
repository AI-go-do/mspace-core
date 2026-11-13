package kr.ac.chosun.mspace.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
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
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관리자 "지금" 탭 (GET /api/admin/rooms/environment, Q-21) + 센서 이벤트 온도·습도 칸(V21).
 * 시계는 서울 2030-03-05 11:10:30 고정. 회원 1–6 학생, 7 관리자. 센서 값은 장치 API로 보낸다.
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RoomEnvironmentApiTest.FixedClock.class})
class RoomEnvironmentApiTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2030-03-05T02:10:30Z"), ZoneId.of("Asia/Seoul"));
    static final long ADMIN = 7;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionOperations tx;

    int seq;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, sensor_event, room_presence, notification "
                + "RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 7; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "env" + i + "@chosun.ac.kr").param("s", "2030000" + i).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
    }

    static String at(String hhmmss) {
        return "2030-03-05T" + hhmmss + "+09:00";
    }

    MvcTestResult sensor(String json) {
        return mvc.post().uri("/api/sensor-events").header("Authorization", "Bearer test-device-token")
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    void co2(int room, int ppm, String extra, String observed) {
        assertThat(sensor("""
                {"eventId":"c-%d","roomId":%d,"deviceId":"sensor-%d","kind":"CO2","ppm":%d%s,"observedAt":"%s"}"""
                .formatted(++seq, room, room, ppm, extra, at(observed)))).hasStatusOk();
    }

    MvcTestResult get(long userId, String query) {
        return mvc.get().uri("/api/admin/rooms/environment" + query).header("X-User-Id", String.valueOf(userId))
                .exchange();
    }

    /** 1번 방 예약. 첫 번째가 대표자 — 인원 검사 트리거가 커밋 때 돌아서 한 트랜잭션에 넣는다 */
    long booking(String start, String end, String status, long... people) {
        return tx.execute(s -> {
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                     checked_in_at)
                            VALUES (1, :owner, CAST(:s AS timestamptz), CAST(:e AS timestamptz), :n, :code, :st,
                                    CASE WHEN :st = 'IN_USE' THEN CAST(:s AS timestamptz) END)
                            RETURNING id
                            """).param("owner", people[0]).param("s", at(start)).param("e", at(end))
                    .param("n", people.length).param("code", "12345" + people[0]).param("st", status)
                    .query((rs, n) -> rs.getLong(1)).single();
            for (long uid : people) {
                jdbc.sql("""
                        INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                        SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                        """).param("rid", id).param("uid", uid).param("owner", uid == people[0]).update();
            }
            return id;
        });
    }

    @Test
    @DisplayName("CO2 이벤트에 온도·습도를 같이 보내면 소수 한 자리로 저장하고, 안 보내도 그대로 된다")
    void climateStored() {
        co2(1, 800, ",\"temperatureC\":24.96,\"humidityPct\":45", "11:05:10");
        co2(1, 810, "", "11:06:10");

        List<Map<String, Object>> rows = jdbc.sql("SELECT temperature_c, humidity_pct FROM sensor_event ORDER BY id")
                .query().listOfRows();
        assertThat(rows.get(0).get("temperature_c").toString()).isEqualTo("25.0");
        assertThat(rows.get(0).get("humidity_pct").toString()).isEqualTo("45.0");
        assertThat(rows.get(1).get("temperature_c")).isNull();
    }

    @Test
    @DisplayName("온도·습도는 CO2에만, 범위 밖(온도 -40–85, 습도 0–100)이면 400")
    void climateValidated() {
        MvcTestResult onOccupancy = sensor("""
                {"eventId":"o-1","roomId":1,"deviceId":"sensor-1","kind":"OCCUPANCY","occupied":true,
                 "temperatureC":25,"observedAt":"%s"}""".formatted(at("11:05:00")));
        MvcTestResult hot = sensor("""
                {"eventId":"c-x","roomId":1,"deviceId":"sensor-1","kind":"CO2","ppm":800,"temperatureC":90,
                 "observedAt":"%s"}""".formatted(at("11:05:00")));
        MvcTestResult wet = sensor("""
                {"eventId":"c-y","roomId":1,"deviceId":"sensor-1","kind":"CO2","ppm":800,"humidityPct":101,
                 "observedAt":"%s"}""".formatted(at("11:05:00")));

        assertThat(onOccupancy).hasStatus(400);
        assertThat(hot).hasStatus(400);
        assertThat(wet).hasStatus(400);
        assertThat(jdbc.sql("SELECT count(*) FROM sensor_event").query((rs, n) -> rs.getLong(1)).single()).isZero();
    }

    @Test
    @DisplayName("관리자가 아니면 403 ADMIN_ONLY, minutes가 1–180 밖이면 400")
    void guards() {
        MvcTestResult student = get(1, "");

        assertThat(student).hasStatus(403);
        assertThat(student).bodyJson().extractingPath("$.code").isEqualTo("ADMIN_ONLY");
        assertThat(get(ADMIN, "?minutes=0")).hasStatus(400);
        assertThat(get(ADMIN, "?minutes=181")).hasStatus(400);
        assertThat(get(ADMIN, "?minutes=180")).hasStatusOk();
    }

    @Test
    @DisplayName("방마다 지금 값·재실·끊김과 지난 N분 1분 평균(값 없는 분은 null)을 준다")
    void environment() {
        co2(1, 800, ",\"temperatureC\":24.9,\"humidityPct\":45", "11:05:10");
        co2(1, 900, ",\"temperatureC\":25.1,\"humidityPct\":47", "11:05:40");
        co2(1, 1000, "", "11:09:20");
        assertThat(sensor("""
                {"eventId":"o-1","roomId":1,"deviceId":"sensor-1","kind":"OCCUPANCY","occupied":true,
                 "observedAt":"%s"}""".formatted(at("11:10:00")))).hasStatusOk();

        MvcTestResult r = get(ADMIN, "?minutes=10");

        assertThat(r).hasStatusOk();
        var json = assertThat(r).bodyJson();
        json.extractingPath("$.now").asString().startsWith("2030-03-05T11:10:30+09:00");
        json.extractingPath("$.minutes").isEqualTo(10);
        json.extractingPath("$.rooms.length()").isEqualTo(5);
        // 1번 방: 센서 살아 있음
        json.extractingPath("$.rooms[0].presence").isEqualTo("OCCUPIED");
        json.extractingPath("$.rooms[0].offline").isEqualTo(false);
        json.extractingPath("$.rooms[0].co2.ppm").isEqualTo(1000);
        json.extractingPath("$.rooms[0].co2.at").asString().startsWith("2030-03-05T11:09:20+09:00");
        json.extractingPath("$.rooms[0].temperatureC").isEqualTo(25.1);   // 온도가 있는 마지막 값
        json.extractingPath("$.rooms[0].humidityPct").isEqualTo(47.0);
        json.extractingPath("$.rooms[0].series.length()").isEqualTo(10);   // 11:01 … 11:10
        json.extractingPath("$.rooms[0].series[0].at").asString().startsWith("2030-03-05T11:01:00+09:00");
        json.extractingPath("$.rooms[0].series[4].ppm").isEqualTo(850);    // 11:05 두 값 평균
        json.extractingPath("$.rooms[0].series[4].temperatureC").isEqualTo(25.0);
        json.extractingPath("$.rooms[0].series[4].humidityPct").isEqualTo(46.0);
        json.extractingPath("$.rooms[0].series[8].ppm").isEqualTo(1000);   // 11:09, 온도 없음
        json.extractingPath("$.rooms[0].series[8].temperatureC").isNull();
        json.extractingPath("$.rooms[0].series[9].ppm").isNull();          // 11:10, 값 없음
        // 2번 방: 신호가 한 번도 없음 → 모름·끊김, 값은 모두 null
        json.extractingPath("$.rooms[1].presence").isEqualTo("UNKNOWN");
        json.extractingPath("$.rooms[1].offline").isEqualTo(true);
        json.extractingPath("$.rooms[1].lastSignalAt").isNull();
        json.extractingPath("$.rooms[1].co2").isNull();
        json.extractingPath("$.rooms[1].series.length()").isEqualTo(10);
        json.extractingPath("$.rooms[1].series[4].ppm").isNull();
    }

    MvcTestResult occupancy(int room, boolean occupied, String sources, String observed) {
        return sensor("""
                {"eventId":"o-%d","roomId":%d,"deviceId":"sensor-%d","kind":"OCCUPANCY","occupied":%s,
                 "sources":%s,"observedAt":"%s"}""".formatted(++seq, room, room, occupied, sources, at(observed)));
    }

    @Test
    @DisplayName("조도(원시값 0–1023)·PIR은 재실이 그대로여도 매번 받은 최신 값, 이상한 값·옛 이벤트·창 밖 값은 건너뛴다")
    void lightAndPir() {
        assertThat(occupancy(3, true, "{\"pir\":true,\"light\":512}", "11:05:00")).hasStatusOk();
        assertThat(occupancy(3, true, "{\"camera\":true,\"people\":2,\"light\":640}", "11:08:00")).hasStatusOk();
        assertThat(occupancy(3, true, "{\"pir\":false,\"light\":\"dark\"}", "11:09:00")).hasStatusOk();
        assertThat(occupancy(3, true, "{\"pir\":\"yes\",\"light\":5000}", "11:10:00")).hasStatusOk();
        assertThat(occupancy(3, true, "{\"pir\":true,\"light\":10}", "11:07:00")).hasStatusOk();   // 늦게 온 옛 것
        assertThat(occupancy(4, false, "{\"pir\":true,\"light\":100}", "10:55:00")).hasStatusOk(); // 창(11:01–) 밖

        var json = assertThat(get(ADMIN, "?minutes=10")).bodyJson();

        json.extractingPath("$.rooms[2].light.raw").isEqualTo(640);
        json.extractingPath("$.rooms[2].light.at").asString().startsWith("2030-03-05T11:08:00+09:00");
        json.extractingPath("$.rooms[2].pir.detected").isEqualTo(false);
        json.extractingPath("$.rooms[2].pir.at").asString().startsWith("2030-03-05T11:09:00+09:00");
        json.extractingPath("$.rooms[3].light").isNull();
        json.extractingPath("$.rooms[3].pir").isNull();
        json.extractingPath("$.rooms[4].light").isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM sensor_event WHERE room_id = 3").query(Long.class).single())
                .as("DB에는 재실이 바뀐 것만 (처음 1건)").isEqualTo(1);
    }

    @Test
    @DisplayName("재실 신호가 끊긴 지 1분이 넘으면 UNKNOWN·offline (방 현황과 같은 기준)")
    void staleSignalIsUnknown() {
        assertThat(sensor("""
                {"eventId":"o-1","roomId":1,"deviceId":"sensor-1","kind":"OCCUPANCY","occupied":false,
                 "observedAt":"%s"}""".formatted(at("11:05:00")))).hasStatusOk();
        jdbc.sql("UPDATE room_presence SET last_received_at = CAST(:t AS timestamptz) WHERE room_id = 1")
                .param("t", at("11:09:00")).update();

        MvcTestResult r = get(ADMIN, "?minutes=5");

        assertThat(r).bodyJson().extractingPath("$.rooms[0].presence").isEqualTo("UNKNOWN");
        assertThat(r).bodyJson().extractingPath("$.rooms[0].offline").isEqualTo(true);
        assertThat(r).bodyJson().extractingPath("$.rooms[0].lastSignalAt").asString()
                .startsWith("2030-03-05T11:09:00+09:00");
    }

    @Test
    @DisplayName("지금 걸린 예약(current)과 다음 예약(next)을 예약 인원과 같이 준다")
    void currentAndNext() {
        long current = booking("11:00:00", "12:30:00", "BOOKED", 1, 2, 3);
        booking("13:00:00", "14:00:00", "BOOKED", 4, 5, 6);

        MvcTestResult r = get(ADMIN, "?minutes=5");

        var json = assertThat(r).bodyJson();
        json.extractingPath("$.rooms[0].current.reservationId").isEqualTo((int) current);
        json.extractingPath("$.rooms[0].current.people").isEqualTo(3);
        json.extractingPath("$.rooms[0].current.checkedIn").isEqualTo(false);
        json.extractingPath("$.rooms[0].current.endAt").asString().startsWith("2030-03-05T12:30:00+09:00");
        json.extractingPath("$.rooms[0].next.startAt").asString().startsWith("2030-03-05T13:00:00+09:00");
        json.extractingPath("$.rooms[0].next.people").isEqualTo(3);
        json.extractingPath("$.rooms[1].current").isNull();
        json.extractingPath("$.rooms[1].next").isNull();
    }
}
