package kr.ac.chosun.mspace.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.ops.application.OpsAutoUseCase;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionOperations;

/**
 * AI 자동 처리 (11/12 지영): 관리자 기록 2종(센서 고장·예약 없는 사용).
 * 회원 1–6 학생, 7 관리자. 날짜는 2030-03-05(서울), 시각은 테스트마다 정한다.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OpsAutoTest.FixedClock.class})
class OpsAutoTest {

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
    static class FixedClock {
        @Bean
        @Primary
        SettableClock opsClock() {
            return new SettableClock();
        }
    }

    @Autowired
    SettableClock clock;

    @Autowired
    TransactionOperations tx;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OpsAutoUseCase ops;

    int seq;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, sensor_event, room_presence, notification, "
                + "ops_action RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 7; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "ops" + i + "@chosun.ac.kr").param("s", "2031000" + i).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
    }

    static Timestamp at(String hhmm) {
        return Timestamp.from(OffsetDateTime.parse("2030-03-05T" + hhmm + ":00+09:00").toInstant());
    }

    void now(String hhmm) {
        clock.now = at(hhmm).toInstant();
    }

    /** 지금부터 m분 (음수면 전) */
    Timestamp min(int m) {
        return Timestamp.from(clock.now.plusSeconds(60L * m));
    }

    /** 예약 1건 (owner와 다음 두 명). status에 맞춰 입실·퇴실 시각을 넣는다 */
    long team(int room, long owner, String start, String end, String status) {
        Long id = tx.execute(t -> insertTeam(room, owner, start, end, status));
        return id;
    }

    private long insertTeam(int room, long owner, String start, String end, String status) {
        boolean in = !status.equals("BOOKED");
        boolean out = status.endsWith("CHECKED_OUT");
        long id = jdbc.sql("""
                        INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                 checked_in_at, checked_out_at)
                        VALUES (:room, :owner, :s, :e, 3, '123456', :st, :in, :out) RETURNING id
                        """).param("room", room).param("owner", owner).param("s", at(start))
                .param("e", at(end)).param("st", status).param("in", in ? at(start) : null)
                .param("out", out ? at(end) : null)
                .query((rs, n) -> rs.getLong(1)).single();
        for (long uid = owner; uid < owner + 3; uid++) {
            jdbc.sql("""
                    INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                    SELECT :rid, :uid, false, start_at, end_at FROM reservation WHERE id = :rid
                    """).param("rid", id).param("uid", uid).update();
        }
        return id;
    }

    void presence(int room, boolean occupied, int sinceMin, int lastMin) {
        jdbc.sql("""
                INSERT INTO room_presence (room_id, occupied, changed_at, trusted_from, last_observed_at, last_received_at)
                VALUES (:room, :occ, :since, :since, :last, :last)
                """).param("room", room).param("occ", occupied).param("since", min(sinceMin))
                .param("last", min(lastMin)).update();
    }

    List<String> recorded() {
        return jdbc.sql("SELECT kind || ':' || room_id || ':' || notified FROM ops_action ORDER BY kind, room_id")
                .query((rs, n) -> rs.getString(1)).list();
    }

    @Test
    @DisplayName("센서 고장·예약 없는 사용은 관리자 기록만 남기고, 관리자만 볼 수 있다")
    void adminRecords() throws Exception {
        now("09:55");
        presence(1, false, -60, -6);     // 6분째 신호 없음 → 고장
        presence(2, true, -12, 0);       // 예약 없이 12분째 있음
        presence(3, true, -12, -2);      // 신호가 끊긴 방의 "있음"은 믿지 않음
        presence(4, true, -12, 0);
        team(4, 1, "10:00", "11:00", "BOOKED");   // 곧 시작하는 팀이 일찍 옴

        assertThat(ops.check()).isEqualTo(2);
        assertThat(recorded()).containsExactly("SENSOR_DOWN:1:0", "UNBOOKED:2:0");
        assertThat(jdbc.sql("SELECT count(*) FROM notification").query((rs, n) -> rs.getLong(1)).single()).isZero();
        assertThat(ops.check()).isZero();

        var r = mvc.get().uri("/api/admin/ops-actions?date=2030-03-05").header("X-User-Id", String.valueOf(ADMIN))
                .exchange();
        assertThat(r).hasStatusOk();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"kind\":\"UNBOOKED\"", "예약 없이 12분째 사람 있음", "\"kind\":\"SENSOR_DOWN\"");
        assertThat(mvc.get().uri("/api/admin/ops-actions").header("X-User-Id", "1").exchange()).hasStatus(403);
    }

    @Test
    @DisplayName("모든 방 신호가 같이 끊기면 네트워크 문제로 보고 센서 고장으로 남기지 않는다")
    void allDownIsNotSensorFault() {
        now("10:00");
        for (int room = 1; room <= 5; room++) {
            presence(room, false, -60, -8);
        }

        assertThat(ops.check()).isZero();
    }
}
