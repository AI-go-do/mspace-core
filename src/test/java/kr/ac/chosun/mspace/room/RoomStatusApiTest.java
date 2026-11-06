package kr.ac.chosun.mspace.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 방 현황 API (FR-DEV-102). 지금 = 2030-03-05 14:00 서울로 고정.
 * 테스트마다 트랜잭션을 롤백해서 서로 데이터가 섞이지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, RoomStatusApiTest.FixedClock.class})
class RoomStatusApiTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2030-03-05T05:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users, room_presence RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 4; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "2023000" + i).update();
        }
    }

    /** 서울 시각 "14:00" → 2030-03-05T14:00+09:00 */
    static String t(String hhmm) {
        return "2030-03-05T" + hhmm + ":00+09:00";
    }

    /** 끝나지 않은 예약은 대표자당 1건이라(one_active_per_owner) 대표자를 나눠 넣는다 */
    void reserve(long owner, int room, String start, String end, String status) {
        jdbc.sql("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                         checked_in_at, checked_out_at, canceled_at)
                VALUES (:room, :owner, :start::timestamptz, :end::timestamptz, 3, lpad(:owner::text, 6, '0'), :status,
                        CASE WHEN :status IN ('IN_USE', 'CHECKED_OUT') THEN :start::timestamptz END,
                        CASE WHEN :status = 'CHECKED_OUT' THEN :start::timestamptz + interval '30 minutes' END,
                        CASE WHEN :status = 'CANCELED' THEN now() END)
                """)
                .param("room", room).param("owner", owner).param("start", t(start)).param("end", t(end))
                .param("status", status).update();
    }

    /** 센서 신호: 마지막으로 받은 시각이 지금보다 secondsAgo초 전 */
    void presence(int room, boolean occupied, int secondsAgo) {
        jdbc.sql("""
                INSERT INTO room_presence (room_id, occupied, changed_at, trusted_from, last_observed_at, last_received_at)
                VALUES (:room, :occ, :at, :at, :at, :at)
                """)
                .param("room", room).param("occ", occupied)
                .param("at", java.sql.Timestamp.from(Instant.parse("2030-03-05T05:00:00Z").minusSeconds(secondsAgo)))
                .update();
    }

    @Test
    @DisplayName("예약이 없으면 방 5개 모두 비어 있고 다음 예약·센서는 비어 있다")
    void allAvailable() {
        assertThat(mvc.get().uri("/api/rooms/status"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.now").isEqualTo("2030-03-05T14:00:00+09:00");
                    json.assertThat().extractingPath("$.availableCount").isEqualTo(5);
                    json.assertThat().extractingPath("$.roomCount").isEqualTo(5);
                    json.assertThat().extractingPath("$.rooms[0].status").isEqualTo("AVAILABLE");
                    json.assertThat().extractingPath("$.rooms[0].currentEndAt").isNull();
                    json.assertThat().extractingPath("$.rooms[0].nextStartAt").isNull();
                    json.assertThat().extractingPath("$.rooms[0].presence").isEqualTo("UNKNOWN");
                });
    }

    @Test
    @DisplayName("사용 중·입실 전 예약은 상태와 끝 시각이 보이고, 빈 방 수에서 빠진다")
    void currentReservation() {
        reserve(1, 1, "13:30", "15:00", "IN_USE");
        reserve(2, 2, "14:00", "15:00", "BOOKED");

        assertThat(mvc.get().uri("/api/rooms/status"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.availableCount").isEqualTo(3);
                    json.assertThat().extractingPath("$.rooms[0].status").isEqualTo("IN_USE");
                    json.assertThat().extractingPath("$.rooms[0].currentStartAt").isEqualTo(t("13:30"));
                    json.assertThat().extractingPath("$.rooms[0].currentEndAt").isEqualTo(t("15:00"));
                    json.assertThat().extractingPath("$.rooms[1].status").isEqualTo("RESERVED");
                });
    }

    @Test
    @DisplayName("다음 예약 시각은 지금 이후 가장 가까운 시작이고, 끝난·취소된 예약은 보지 않는다")
    void nextStart() {
        reserve(1, 1, "17:00", "18:00", "BOOKED");
        reserve(2, 1, "15:00", "16:00", "BOOKED");
        reserve(3, 1, "14:30", "15:00", "CANCELED");
        reserve(4, 1, "12:00", "13:00", "CHECKED_OUT");

        assertThat(mvc.get().uri("/api/rooms/status"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.rooms[0].status").isEqualTo("AVAILABLE");
                    json.assertThat().extractingPath("$.rooms[0].nextStartAt").isEqualTo(t("15:00"));
                    json.assertThat().extractingPath("$.availableCount").isEqualTo(5);
                });
    }

    @Test
    @DisplayName("지금 끝나는 예약은 이미 끝난 것으로 본다 (반열린 구간 [시작, 끝))")
    void endsNow() {
        reserve(1, 1, "13:00", "14:00", "IN_USE");

        assertThat(mvc.get().uri("/api/rooms/status"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.rooms[0].status").isEqualTo("AVAILABLE"));
    }

    @Test
    @DisplayName("센서 재실: 1분 안에 받은 신호면 있음·없음, 끊겼으면 모름")
    void sensorPresence() {
        presence(1, true, 10);
        presence(2, false, 10);
        presence(3, false, 61);

        assertThat(mvc.get().uri("/api/rooms/status"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.rooms[0].presence").isEqualTo("OCCUPIED");
                    json.assertThat().extractingPath("$.rooms[1].presence").isEqualTo("EMPTY");
                    json.assertThat().extractingPath("$.rooms[2].presence").isEqualTo("UNKNOWN");
                    json.assertThat().extractingPath("$.rooms[3].presence").isEqualTo("UNKNOWN");
                    // 센서는 참고일 뿐, 비었는지는 예약 기준
                    json.assertThat().extractingPath("$.rooms[0].status").isEqualTo("AVAILABLE");
                });
    }
}
