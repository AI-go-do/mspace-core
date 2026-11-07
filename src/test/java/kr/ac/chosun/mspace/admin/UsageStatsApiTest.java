package kr.ac.chosun.mspace.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관리자 이용 통계 (GET /api/stats/usage). 어제(서울) 하루에 예약·센서 기록을 넣고 숫자를 맞춘다.
 * 회원 1–6 학생, 7 관리자.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, UsageStatsApiTest.FixedClock.class})
class UsageStatsApiTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 서울 2026-11-05 12:00. 자정 근처에 돌려도 "오늘"이 흔들리지 않게 고정한다 (CodeRabbit) */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), SEOUL);
    static final long ADMIN = 7;
    static final LocalDate TODAY = LocalDate.now(CLOCK);
    static final LocalDate DAY = TODAY.minusDays(1);

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

    long r1;

    static Timestamp at(int h, int m) {
        return Timestamp.from(DAY.atTime(h, m).atZone(SEOUL).toInstant());
    }

    static Timestamp before(int h) {
        return Timestamp.from(DAY.minusDays(1).atTime(h, 0).atZone(SEOUL).toInstant());
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE sensor_event, notification, reservation, reservation_participant, users RESTART IDENTITY CASCADE")
                .update();
        for (int i = 1; i <= 7; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "202400" + (30 + i)).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();

        // 1번 방: 쓴 것(90분), 노쇼, 취소, 강제 퇴실(20분, 짧은 이용). 회원 1·2·3
        r1 = reservation(1, 1, at(10, 0), at(12, 0), "CHECKED_OUT", at(10, 0), at(11, 30), null);
        reservation(1, 1, at(13, 0), at(14, 0), "NO_SHOW", null, null, null);
        reservation(1, 1, at(15, 0), at(16, 0), "CANCELED", null, null, null);
        reservation(1, 1, at(16, 0), at(18, 0), "CHECKED_OUT", at(16, 0), at(16, 20), ADMIN);
        // 2번 방: 자동 퇴실(50분). 회원 4·5·6
        reservation(2, 4, at(10, 0), at(11, 0), "AUTO_CHECKED_OUT", at(10, 10), at(11, 0), null);

        // 1번 방 재실: 어제 23시 비어 있음 → 09:50 있음 → 11:40 없음 → 20:00 있음 → 21:00 없음
        occupancy(1, before(23), false);
        occupancy(1, at(9, 50), true);
        occupancy(1, at(11, 40), false);
        occupancy(1, at(20, 0), true);
        occupancy(1, at(21, 0), false);
        // 2번 방: 그 전날 22시 "있음" 뒤로 기록 없음 → 하루 내내 있음
        occupancy(2, before(22), true);
        co2(1, at(10, 30), 800);
        co2(1, at(10, 31), 1200);
        co2(1, at(10, 32), 1300);
        // 환기 알림 한 번 = 같은 시각에 사용자 둘
        for (long u = 1; u <= 2; u++) {
            jdbc.sql("""
                            INSERT INTO notification (user_id, reservation_id, kind, title, body, created_at)
                            VALUES (:u, :r, 'VENTILATE', '환기해 주세요', 'x', :t)
                            """).param("u", u).param("r", r1).param("t", at(10, 33)).update();
        }
    }

    long reservation(int room, long firstUser, Timestamp start, Timestamp end, String status, Timestamp in,
                     Timestamp out, Long forcedBy) {
        return tx.execute(s -> {
            long id = jdbc.sql("""
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                     checked_in_at, checked_out_at, canceled_at, forced_by, created_at)
                            VALUES (:room, :owner, :start, :end, 3, '123456', :status, :in, :out,
                                    CASE WHEN :status = 'CANCELED' THEN CAST(:start AS timestamptz) END, :forced,
                                    CAST(:start AS timestamptz) - interval '1 day')
                            RETURNING id
                            """)
                    .param("room", room).param("owner", firstUser).param("start", start).param("end", end)
                    .param("status", status).param("in", in).param("out", out).param("forced", forcedBy)
                    .query(Long.class).single();
            for (long u = firstUser; u < firstUser + 3; u++) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                                """).param("rid", id).param("uid", u).param("owner", u == firstUser).update();
            }
            return id;
        });
    }

    void occupancy(int room, Timestamp t, boolean occupied) {
        jdbc.sql("""
                        INSERT INTO sensor_event (event_id, room_id, device_id, kind, occupied, observed_at, received_at)
                        VALUES (gen_random_uuid()::text, :room, 'test', 'OCCUPANCY', :o, :t, :t)
                        """).param("room", room).param("o", occupied).param("t", t).update();
    }

    void co2(int room, Timestamp t, int ppm) {
        jdbc.sql("""
                        INSERT INTO sensor_event (event_id, room_id, device_id, kind, ppm, observed_at, received_at)
                        VALUES (gen_random_uuid()::text, :room, 'test', 'CO2', :p, :t, :t)
                        """).param("room", room).param("p", ppm).param("t", t).update();
    }

    MvcTestResult usage(long userId, String query) {
        return mvc.get().uri("/api/stats/usage" + query).header("X-User-Id", String.valueOf(userId)).exchange();
    }

    MvcTestResult day() {
        return usage(ADMIN, "?from=" + DAY + "&to=" + DAY);
    }

    @Test
    @DisplayName("방별 예약 수·노쇼율·실사용률·이용 시간·짧은 이용·이용자 수")
    void roomNumbers() {
        MvcTestResult r = day();

        assertThat(r).hasStatus(200);
        var json = assertThat(r).bodyJson();
        json.extractingPath("$.days").isEqualTo(1);
        json.extractingPath("$.rooms[0].roomId").isEqualTo(1);
        json.extractingPath("$.rooms[0].reservations.total").isEqualTo(4);
        json.extractingPath("$.rooms[0].reservations.canceled").isEqualTo(1);
        json.extractingPath("$.rooms[0].reservations.noShow").isEqualTo(1);
        json.extractingPath("$.rooms[0].reservations.used").isEqualTo(2);
        json.extractingPath("$.rooms[0].reservations.forced").isEqualTo(1);
        json.extractingPath("$.rooms[0].rates.noShow").isEqualTo(0.333);
        json.extractingPath("$.rooms[0].rates.actualUse").isEqualTo(0.667);
        json.extractingPath("$.rooms[0].rates.utilization").isEqualTo(0.076);   // 110분 / 1440분
        json.extractingPath("$.rooms[0].minutes.booked").isEqualTo(300);
        json.extractingPath("$.rooms[0].minutes.used").isEqualTo(110);
        json.extractingPath("$.rooms[0].minutes.avgUsed").isEqualTo(55);
        json.extractingPath("$.rooms[0].shortUses").isEqualTo(1);
        json.extractingPath("$.rooms[0].uniqueUsers").isEqualTo(3);
        json.extractingPath("$.rooms[1].reservations.autoCheckedOut").isEqualTo(1);
        json.extractingPath("$.rooms[1].minutes.used").isEqualTo(50);
        json.extractingPath("$.rooms[2].reservations.total").isEqualTo(0);
        json.extractingPath("$.rooms[2].rates.noShow").isNull();
    }

    @Test
    @DisplayName("전체 합계와 시간대별(서울 시작 시각, 취소 빼고)")
    void totalAndHours() {
        var json = assertThat(day()).bodyJson();

        json.extractingPath("$.total.roomId").isNull();
        json.extractingPath("$.total.reservations.total").isEqualTo(5);
        json.extractingPath("$.total.reservations.used").isEqualTo(3);
        json.extractingPath("$.total.minutes.used").isEqualTo(160);
        json.extractingPath("$.total.uniqueUsers").isEqualTo(6);
        json.extractingPath("$.byHour.length()").isEqualTo(24);
        json.extractingPath("$.byHour[10].reservations").isEqualTo(2);
        json.extractingPath("$.byHour[10].used").isEqualTo(2);
        json.extractingPath("$.byHour[13].reservations").isEqualTo(1);
        json.extractingPath("$.byHour[13].used").isEqualTo(0);
        json.extractingPath("$.byHour[15].reservations").isEqualTo(0);
        json.extractingPath("$.byHour[16].used").isEqualTo(1);
    }

    @Test
    @DisplayName("센서: CO2 평균·최고·기준 초과 비율, 환기 알림 수, 재실 시간과 예약 없이 있던 시간")
    void sensorNumbers() {
        var json = assertThat(day()).bodyJson();

        json.extractingPath("$.rooms[0].sensor.co2Readings").isEqualTo(3);
        json.extractingPath("$.rooms[0].sensor.co2AvgPpm").isEqualTo(1100);
        json.extractingPath("$.rooms[0].sensor.co2MaxPpm").isEqualTo(1300);
        json.extractingPath("$.rooms[0].sensor.co2OverRate").isEqualTo(0.667);
        json.extractingPath("$.rooms[0].sensor.thresholdPpm").isEqualTo(1000);
        json.extractingPath("$.rooms[0].sensor.ventilationAlerts").isEqualTo(1);
        // 09:50–11:40(110) + 20:00–21:00(60). 입실 10:00–11:30·16:00–16:20을 빼면 10 + 10 + 60
        json.extractingPath("$.rooms[0].sensor.occupiedMinutes").isEqualTo(170);
        json.extractingPath("$.rooms[0].sensor.occupiedWithoutReservationMinutes").isEqualTo(80);
        // 2번 방은 전날 "있음"이 하루 내내 이어짐. 입실 10:10–11:00(50분) 빼고
        json.extractingPath("$.rooms[1].sensor.occupiedMinutes").isEqualTo(1440);
        json.extractingPath("$.rooms[1].sensor.occupiedWithoutReservationMinutes").isEqualTo(1390);
        json.extractingPath("$.rooms[2].sensor.co2AvgPpm").isNull();
        json.extractingPath("$.total.sensor.co2Readings").isEqualTo(3);
    }

    long reservationId(int room, Timestamp start) {
        return jdbc.sql("SELECT id FROM reservation WHERE room_id = :room AND start_at = :start")
                .param("room", room).param("start", start).query((rs, n) -> rs.getLong(1)).single();
    }

    void noShowSoon(long reservationId, long userId) {
        jdbc.sql("INSERT INTO notification_log (reservation_id, user_id, kind) VALUES (:r, :u, 'NO_SHOW_SOON')")
                .param("r", reservationId).param("u", userId).update();
    }

    void ventilate(long reservationId, long userId, Timestamp t) {
        jdbc.sql("""
                        INSERT INTO notification (user_id, reservation_id, kind, title, body, created_at)
                        VALUES (:u, :r, 'VENTILATE', '환기해 주세요', 'x', :t)
                        """).param("u", userId).param("r", reservationId).param("t", t).update();
    }

    @Test
    @DisplayName("알림 효과: 노쇼 직전 알림 2건 중 1건 입실, 환기 알림 2번 중 15분 안에 기준 이하로 내려간 것 1번")
    void alertEffect() {
        noShowSoon(r1, 1);                                  // 알림 뒤 입실
        noShowSoon(reservationId(1, at(13, 0)), 1);         // 알림 받고도 노쇼
        co2(1, at(10, 45), 900);                            // 10:33 알림 → 12분 뒤 900 (내려감)
        long r2 = reservationId(2, at(10, 0));
        ventilate(r2, 4, at(10, 20));                       // 2번 방 알림 → 15분 안에 값 없음
        co2(2, at(10, 50), 800);                            // 30분 뒤라 효과로 치지 않는다

        var json = assertThat(day()).bodyJson();
        json.extractingPath("$.rooms[0].alerts.noShowWarned").isEqualTo(2);
        json.extractingPath("$.rooms[0].alerts.noShowCheckedIn").isEqualTo(1);
        json.extractingPath("$.rooms[0].alerts.noShowCheckedInRate").isEqualTo(0.5);
        json.extractingPath("$.rooms[0].alerts.ventilationChecked").isEqualTo(1);
        json.extractingPath("$.rooms[0].alerts.ventilationResolved").isEqualTo(1);
        json.extractingPath("$.rooms[0].alerts.resolvedWithinMinutes").isEqualTo(15);
        json.extractingPath("$.rooms[1].alerts.ventilationChecked").isEqualTo(1);
        json.extractingPath("$.rooms[1].alerts.ventilationResolved").isEqualTo(0);
        json.extractingPath("$.rooms[1].alerts.ventilationResolvedRate").isEqualTo(0.0);
        json.extractingPath("$.rooms[2].alerts.noShowCheckedInRate").isNull();
        json.extractingPath("$.total.alerts.noShowWarned").isEqualTo(2);
        json.extractingPath("$.total.alerts.ventilationChecked").isEqualTo(2);
        json.extractingPath("$.total.alerts.ventilationResolvedRate").isEqualTo(0.5);
    }

    @Test
    @DisplayName("알림 효과: 확인 시간(15분)이 아직 안 지난 환기 알림과 입실 마감 전 노쇼 알림은 세지 않는다")
    void alertEffectPending() {
        // 지금 = 오늘 12:00. 11:50 알림은 12:05까지 봐야 해서 아직 결과가 없다
        Timestamp now = Timestamp.from(TODAY.atTime(11, 50).atZone(SEOUL).toInstant());
        long today = reservation(3, 1, Timestamp.from(TODAY.atTime(11, 30).atZone(SEOUL).toInstant()),
                Timestamp.from(TODAY.atTime(12, 30).atZone(SEOUL).toInstant()), "IN_USE", now, null, null);
        ventilate(today, 1, now);
        long later = reservation(4, 4, Timestamp.from(TODAY.atTime(12, 0).atZone(SEOUL).toInstant()),
                Timestamp.from(TODAY.atTime(13, 0).atZone(SEOUL).toInstant()), "BOOKED", null, null, null);
        noShowSoon(later, 4);                               // 입실 마감 12:15 전

        var json = assertThat(usage(ADMIN, "?from=" + TODAY + "&to=" + TODAY)).bodyJson();
        json.extractingPath("$.total.alerts.ventilationChecked").isEqualTo(0);
        json.extractingPath("$.total.alerts.noShowWarned").isEqualTo(0);
        json.extractingPath("$.total.alerts.ventilationResolvedRate").isNull();
    }

    @Test
    @DisplayName("학생은 403 ADMIN_ONLY")
    void studentForbidden() {
        MvcTestResult r = usage(1, "");

        assertThat(r).hasStatus(403);
        assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("ADMIN_ONLY");
    }

    @Test
    @DisplayName("from이 to보다 늦거나 92일을 넘으면 400, 날짜를 빼면 오늘까지 7일")
    void range() {
        assertThat(usage(ADMIN, "?from=2026-11-10&to=2026-11-01")).hasStatus(400);
        assertThat(usage(ADMIN, "?from=2026-01-01&to=2026-12-31")).hasStatus(400);
        assertThat(usage(ADMIN, "?from=1900-01-01&to=1900-01-02")).hasStatus(400);
        MvcTestResult r = usage(ADMIN, "");
        assertThat(r).hasStatus(200);
        assertThat(r).bodyJson().extractingPath("$.days").isEqualTo(7);
        assertThat(r).bodyJson().extractingPath("$.to").isEqualTo(TODAY.toString());
        assertThat(r).bodyJson().extractingPath("$.total.reservations.total").isEqualTo(5);
    }
}
