package kr.ac.chosun.mspace.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

/**
 * 취소 가능성 (11/9 지영). 오늘은 2030-03-05(화) 10:00. 칸 기본 취소율은 지난 8주의 같은 요일·같은 시작 시 미리 예약으로 센다.
 * 회원 1–12, 예약은 대표자 + 그다음 두 사람(3명)으로 SQL로 바로 넣는다.
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RelayApiTest.MovableClock.class})
class RelayChanceApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RelayApiTest.SettableClock clock;

    static Instant at(String date, String time) {
        return OffsetDateTime.parse(date + "T" + time + ":00+09:00").toInstant();
    }

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE relay_offer, relay_wait, notification, reservation, reservation_participant, users "
                + "RESTART IDENTITY CASCADE").update();
        for (int i = 1; i <= 12; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "u" + i + "@chosun.ac.kr").param("s", "%08d".formatted(20230000 + i)).update();
        }
        clock.now = at("2030-03-05", "10:00");
    }

    /** 예약 한 건 (대표자 owner + owner+1, owner+2). status는 BOOKED·CANCELED·CHECKED_OUT */
    void reservation(int room, int owner, Instant start, Instant created, String status) {
        Instant end = start.plusSeconds(3600);
        boolean canceled = status.equals("CANCELED");
        boolean used = status.equals("CHECKED_OUT");
        // 인원 검사가 문장 끝에 돌아서 예약과 참여자를 한 문장으로 넣는다
        jdbc.sql("""
                        WITH r AS (
                            INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, status, access_code,
                                                     created_at, canceled_at, checked_in_at, checked_out_at)
                            VALUES (:room, :owner, :start, :end, 3, :status, '123456', :created,
                                    :canceled::timestamptz, :in::timestamptz, :out::timestamptz)
                            RETURNING id
                        )
                        INSERT INTO reservation_participant (reservation_id, user_id)
                        SELECT r.id, u FROM r, generate_series(:owner::bigint, :owner::bigint + 2) u
                        """)
                .param("room", room).param("owner", owner).param("start", Timestamp.from(start))
                .param("end", Timestamp.from(end)).param("status", status).param("created", Timestamp.from(created))
                .param("canceled", canceled ? Timestamp.from(start.minusSeconds(86400)) : null, Types.TIMESTAMP)
                .param("in", used ? Timestamp.from(start) : null, Types.TIMESTAMP)
                .param("out", used ? Timestamp.from(end) : null, Types.TIMESTAMP)
                .update();
    }

    MvcTestResult chances(int user, String date) {
        return mvc.get().uri("/api/relay/chances?date=" + date).header("X-User-Id", String.valueOf(user)).exchange();
    }

    @Test
    @DisplayName("기록이 적으면 시작값 15%로 보고, 3일 이상 미리 잡은 예약은 ×1.3 → 20%. 줄 선 사람 수도 같이 준다")
    void fewRecordsUsesStartRate() {
        // given: 1번 방 14:00–15:00, 3일 전에 잡음. 그 칸에 2명이 기다림
        reservation(1, 1, at("2030-03-05", "14:00"), at("2030-03-02", "09:00"), "BOOKED");
        for (int user : new int[] {4, 7}) {
            jdbc.sql("INSERT INTO relay_wait (user_id, room_id, start_at, created_at) VALUES (:u, 1, :s, now())")
                    .param("u", user).param("s", Timestamp.from(at("2030-03-05", "14:30"))).update();
        }

        // when
        MvcTestResult r = chances(10, "2030-03-05");

        // then
        assertThat(r).hasStatusOk().bodyJson()
                .hasPathSatisfying("$[0].roomId", v -> v.assertThat().isEqualTo(1))
                .hasPathSatisfying("$[0].startAt", v -> v.assertThat().isEqualTo("2030-03-05T14:00:00+09:00"))
                .hasPathSatisfying("$[0].percent", v -> v.assertThat().isEqualTo(20))
                .hasPathSatisfying("$[0].reasons", v -> v.assertThat().asList().containsExactly("FEW_RECORDS", "BOOKED_EARLY"))
                .hasPathSatisfying("$[0].waiting", v -> v.assertThat().isEqualTo(2));
    }

    @Test
    @DisplayName("지난 8주 같은 요일·시간대 32건 중 16건이 30분 전까지 취소됐으면 50%, 직전에 잡은 예약은 ×0.6 → 30%")
    void slotHistoryDrivesChance() {
        // given: 지난 8번의 화요일 14:00에 방 1–4 예약 32건, 그중 절반 취소(하루 전)
        for (int week = 1; week <= 8; week++) {
            Instant start = at("2030-03-05", "14:00").minusSeconds(week * 7L * 86400);
            for (int room = 1; room <= 4; room++) {
                reservation(room, 1, start, start.minusSeconds(3 * 86400), room <= 2 ? "CANCELED" : "CHECKED_OUT");
            }
        }
        // 오늘 14:00: 1번 방은 4일 전, 2번 방은 오늘 아침에 잡음
        reservation(1, 1, at("2030-03-05", "14:00"), at("2030-03-01", "09:00"), "BOOKED");
        reservation(2, 4, at("2030-03-05", "14:00"), at("2030-03-05", "09:00"), "BOOKED");

        // when
        MvcTestResult r = chances(10, "2030-03-05");

        // then: 높은 순
        assertThat(r).hasStatusOk().bodyJson()
                .hasPathSatisfying("$[0].roomId", v -> v.assertThat().isEqualTo(1))
                .hasPathSatisfying("$[0].percent", v -> v.assertThat().isEqualTo(65))
                .hasPathSatisfying("$[0].reasons", v -> v.assertThat().asList()
                        .containsExactly("SLOT_OFTEN_CANCELED", "BOOKED_EARLY"))
                .hasPathSatisfying("$[1].roomId", v -> v.assertThat().isEqualTo(2))
                .hasPathSatisfying("$[1].percent", v -> v.assertThat().isEqualTo(30))
                .hasPathSatisfying("$[1].reasons", v -> v.assertThat().asList()
                        .containsExactly("SLOT_OFTEN_CANCELED", "BOOKED_LATE"));
    }

    @Test
    @DisplayName("내가 들어 있는 예약과 시작 30분 전이 지난 예약은 빼고, 예약 기간 밖 날짜는 400")
    void excludesMineAndTooLate() {
        // given: 1번 방 14:00(회원 1·2·3), 2번 방 10:30 시작(딱 30분 전이라 못 넘김), 3번 방 15:00(회원 7·8·9)
        reservation(1, 1, at("2030-03-05", "14:00"), at("2030-03-01", "09:00"), "BOOKED");
        reservation(2, 4, at("2030-03-05", "10:30"), at("2030-03-01", "09:00"), "BOOKED");
        reservation(3, 7, at("2030-03-05", "15:00"), at("2030-03-01", "09:00"), "BOOKED");

        // when: 참여자인 회원 2가 본다
        MvcTestResult r = chances(2, "2030-03-05");

        // then: 3번 방만
        assertThat(r).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.length()", v -> v.assertThat().isEqualTo(1))
                .hasPathSatisfying("$[0].roomId", v -> v.assertThat().isEqualTo(3));
        assertThat(chances(2, LocalDate.of(2030, 3, 12).toString())).hasStatus(400).bodyJson()
                .extractingPath("$.code").isEqualTo("ROOM_DATE_OUT_OF_RANGE");
        assertThat(chances(2, "2030-03-04")).hasStatus(400);
    }
}
