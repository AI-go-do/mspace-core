package kr.ac.chosun.mspace.sensor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.sensor.application.LightLeftOnUseCase;
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
import org.springframework.transaction.support.TransactionOperations;

/**
 * 빈 방 불 켜짐 (11/12 지영): 마지막 팀 알림 + 관리자 "지금" 탭 표시.
 * 기준 600, 10분, 마지막 팀은 처음 켜짐 30분 안 퇴실. 회원 1–6 학생, 7 관리자.
 * 조도 최신 값과 켜짐 시각이 메모리라 테스트마다 다른 방을 쓴다.
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, LightLeftOnTest.MovableClock.class})
class LightLeftOnTest {

    static final long ADMIN = 7;

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
    static class MovableClock {
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
    TransactionOperations tx;

    @Autowired
    SettableClock clock;

    @Autowired
    LightLeftOnUseCase light;

    int seq;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, sensor_event, room_presence, notification "
                + "RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 7; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "light" + i + "@chosun.ac.kr").param("s", "2030000" + i).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
    }

    static String at(String hhmm) {
        return "2030-03-05T" + hhmm + ":00+09:00";
    }

    void now(String hhmm) {
        clock.now = OffsetDateTime.parse(at(hhmm)).toInstant();
    }

    /** 지금 시각에 재실 신호. light가 null이면 조도 없이 보낸다 */
    void signal(int room, boolean occupied, Integer lux) {
        String sources = lux == null ? "{\"pir\":false}" : "{\"pir\":false,\"light\":%d}".formatted(lux);
        assertThat(mvc.post().uri("/api/sensor-events").header("Authorization", "Bearer test-device-token")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"l-%d","roomId":%d,"deviceId":"sensor-%d","kind":"OCCUPANCY","occupied":%s,
                         "sources":%s,"observedAt":"%s"}""".formatted(++seq, room, room, occupied, sources,
                        clock.now.toString()))
                .exchange()).hasStatusOk();
    }

    /** 방 예약 1건(회원 1–3). checkedOut이 있으면 그 시각에 퇴실, 없으면 사용 중 */
    long team(int room, String start, String end, String checkedOut) {
        return tx.execute(s -> {
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                     checked_in_at, checked_out_at)
                            VALUES (:room, 1, CAST(:s AS timestamptz), CAST(:e AS timestamptz), 3, '123456', :st,
                                    CAST(:s AS timestamptz), CAST(:out AS timestamptz))
                            RETURNING id
                            """).param("room", room).param("s", at(start)).param("e", at(end))
                    .param("st", checkedOut == null ? "IN_USE" : "CHECKED_OUT")
                    .param("out", checkedOut == null ? null : at(checkedOut))
                    .query((rs, n) -> rs.getLong(1)).single();
            for (long uid = 1; uid <= 3; uid++) {
                jdbc.sql("""
                        INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                        SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                        """).param("rid", id).param("uid", uid).param("owner", uid == 1).update();
            }
            return id;
        });
    }

    List<Long> notified() {
        return jdbc.sql("SELECT user_id FROM notification WHERE kind = 'LIGHT_LEFT_ON' ORDER BY user_id")
                .query(Long.class).list();
    }

    Object leftOnSince(int room) throws java.io.UnsupportedEncodingException {
        var r = mvc.get().uri("/api/admin/rooms/environment?minutes=10").header("X-User-Id", String.valueOf(ADMIN))
                .exchange();
        assertThat(r).hasStatusOk();
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                "$.rooms[%d].lightLeftOn".formatted(room - 1));
    }

    @Test
    @DisplayName("퇴실 뒤 빈 방 조도가 기준 이상으로 10분 이어지면 마지막 팀에게 1번 알리고 관리자에 불 켜짐, 끄면 사라진다")
    void lastTeamNotifiedOnce() throws Exception {
        long id = team(1, "09:00", "10:00", "09:55");
        now("10:01");
        signal(1, false, 700);
        assertThat(light.check()).isZero();
        assertThat(leftOnSince(1)).isNull();

        now("10:11");
        signal(1, false, 700);
        assertThat(light.check()).isEqualTo(3);
        assertThat(notified()).containsExactly(1L, 2L, 3L);
        assertThat(jdbc.sql("SELECT DISTINCT reservation_id FROM notification").query(Long.class).single())
                .isEqualTo(id);
        assertThat(leftOnSince(1).toString()).contains("since=2030-03-05T10:01:00+09:00");

        now("10:12");
        signal(1, false, 700);
        assertThat(light.check()).isZero();   // 예약마다 1번
        assertThat(notified()).hasSize(3);

        now("10:13");
        signal(1, false, 100);
        light.check();
        assertThat(leftOnSince(1)).isNull();
    }

    @Test
    @DisplayName("사람이 있거나 지금 예약이 있으면 밝아도 불 켜짐이 아니다")
    void occupiedOrBookedIsNotLeftOn() throws Exception {
        now("10:01");
        signal(2, true, 900);
        signal(3, false, 900);
        team(3, "10:00", "11:00", null);
        light.check();
        now("10:20");
        signal(2, true, 900);
        signal(3, false, 900);

        assertThat(light.check()).isZero();
        assertThat(leftOnSince(2)).isNull();
        assertThat(leftOnSince(3)).isNull();
    }

    @Test
    @DisplayName("마지막 팀이 켜짐을 보기 30분보다 전에 나갔으면 알리지 않고 관리자 표시만 한다")
    void oldTeamNotNotified() throws Exception {
        team(4, "07:00", "08:00", "08:00");
        now("10:01");
        signal(4, false, 700);
        light.check();
        now("10:11");
        signal(4, false, 700);

        assertThat(light.check()).isZero();
        assertThat(notified()).isEmpty();
        assertThat(leftOnSince(4).toString()).contains("since=2030-03-05T10:01:00+09:00");
    }

    @Test
    @DisplayName("조도가 1분 넘게 안 오면 모르는 것이라 켜짐을 처음부터 다시 센다")
    void staleLightResets() throws Exception {
        now("10:01");
        signal(5, false, 700);
        light.check();
        now("10:05");
        signal(5, false, null);   // 조도 없이 재실만
        light.check();
        now("10:11");
        signal(5, false, 700);

        assertThat(light.check()).isZero();
        assertThat(leftOnSince(5)).isNull();
    }
}
