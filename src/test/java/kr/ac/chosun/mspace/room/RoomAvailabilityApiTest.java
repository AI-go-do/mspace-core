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
 * 빈 시간 조회 API. 지금 = 2026-11-03(화) 10:10 서울로 고정.
 * 테스트마다 트랜잭션을 롤백해서 서로 데이터가 섞이지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, RoomAvailabilityApiTest.FixedClock.class})
class RoomAvailabilityApiTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-03T01:10:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void member() {
        jdbc.sql("INSERT INTO users (id, email, student_id, name, verified_at) OVERRIDING SYSTEM VALUE "
                + "VALUES (1, 'a@chosun.ac.kr', '20230001', '홍길동', now())").update();
    }

    /** 테스트용 예약 1건. 인원 확인 트리거는 커밋 때 도는데 테스트는 롤백하므로 참여자는 넣지 않는다 */
    void reserve(int roomId, String start, String end, String status) {
        reserve(1, roomId, start, end, status);
    }

    /** 대표자를 정해 넣는다. 끝나지 않은 예약은 대표자당 1건이라(one_active_per_owner) 여러 건이면 대표자를 나눈다 */
    void reserve(long ownerId, int roomId, String start, String end, String status) {
        jdbc.sql("""
                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status, canceled_at)
                VALUES (:room, :owner, :start::timestamptz, :end::timestamptz, 3, '123456', :status,
                        CASE WHEN :status = 'CANCELED' THEN now() END)
                """)
                .param("room", roomId).param("owner", ownerId).param("start", start).param("end", end).param("status", status)
                .update();
    }

    @Test
    @DisplayName("예약 없는 날은 방 5개 모두 30분 칸 48개가 비어 있다")
    void emptyDay() {
        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-04"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.slotMinutes").isEqualTo(30);
                    json.assertThat().extractingPath("$.rooms.length()").isEqualTo(5);
                    json.assertThat().extractingPath("$.rooms[4].maxPeople").isEqualTo(8);
                    json.assertThat().extractingPath("$.rooms[0].slots.length()").isEqualTo(48);
                    json.assertThat().extractingPath("$.rooms[0].slots[?(@.available == false)]").asArray().isEmpty();
                    json.assertThat().extractingPath("$.rooms[0].slots[28].startAt").isEqualTo("2026-11-04T14:00:00+09:00");
                });
    }

    @Test
    @DisplayName("1번 방 14:00-16:00 예약이면 그 4칸만 막히고 16:00과 다른 방은 비어 있다")
    void bookedSlots() {
        reserve(1, "2026-11-04 14:00+09", "2026-11-04 16:00+09", "BOOKED");

        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-04"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.rooms[0].slots[?(@.available == false)].startAt").asArray()
                            .containsExactly("2026-11-04T14:00:00+09:00", "2026-11-04T14:30:00+09:00",
                                    "2026-11-04T15:00:00+09:00", "2026-11-04T15:30:00+09:00");
                    json.assertThat().extractingPath("$.rooms[1].slots[28].available").isEqualTo(true);
                });
    }

    @Test
    @DisplayName("취소된 예약은 빈 시간으로 보인다")
    void canceledIsFree() {
        reserve(1, "2026-11-04 14:00+09", "2026-11-04 16:00+09", "CANCELED");

        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-04"))
                .bodyJson()
                .extractingPath("$.rooms[0].slots[28].available").isEqualTo(true);
    }

    @Test
    @DisplayName("자정을 넘긴 예약(23:00-01:00)은 다음 날 00:00, 00:30 칸도 막는다")
    void crossesMidnight() {
        reserve(2, "2026-11-04 23:00+09", "2026-11-05 01:00+09", "BOOKED");

        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-05"))
                .bodyJson()
                .satisfies(json -> json.assertThat()
                        .extractingPath("$.rooms[1].slots[?(@.available == false)].startAt").asArray()
                        .containsExactly("2026-11-05T00:00:00+09:00", "2026-11-05T00:30:00+09:00"));
    }

    @Test
    @DisplayName("오늘 10:10에 걸친 끝나지 않은 예약이 있는 방만 occupiedNow=true. 10:00에 끝난 예약·다른 날은 false")
    void occupiedNow() {
        jdbc.sql("INSERT INTO users (id, email, student_id, name, verified_at) OVERRIDING SYSTEM VALUE "
                + "VALUES (2, 'b@chosun.ac.kr', '20230002', '김지영', now())").update();
        reserve(1, 1, "2026-11-03 09:30+09", "2026-11-03 10:30+09", "BOOKED");   // 지금(10:10) 사용 중
        reserve(2, 2, "2026-11-03 09:00+09", "2026-11-03 10:00+09", "BOOKED");   // 이미 끝남
        reserve(3, "2026-11-03 09:30+09", "2026-11-03 10:30+09", "CANCELED"); // 취소는 안 침

        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-03"))
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.rooms[0].occupiedNow").isEqualTo(true);
                    json.assertThat().extractingPath("$.rooms[1].occupiedNow").isEqualTo(false);
                    json.assertThat().extractingPath("$.rooms[2].occupiedNow").isEqualTo(false);
                    json.assertThat().extractingPath("$.rooms[3].occupiedNow").isEqualTo(false);
                });
        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-04"))
                .bodyJson()
                .extractingPath("$.rooms[0].occupiedNow").isEqualTo(false);
    }

    @Test
    @DisplayName("오늘 10:10이면 10:00 칸까지는 지난 칸이라 막히고 10:30부터 비어 있다")
    void pastSlots() {
        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-03"))
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.rooms[0].slots[20].available").isEqualTo(false);
                    json.assertThat().extractingPath("$.rooms[0].slots[21].available").isEqualTo(true);
                });
    }

    @Test
    @DisplayName("사용 불가 방은 목록에 나오지 않는다")
    void inactiveRoomHidden() {
        jdbc.sql("UPDATE rooms SET is_active = false WHERE id = 3").update();

        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-04"))
                .bodyJson()
                .extractingPath("$.rooms[*].roomId").asArray().containsExactly(1, 2, 4, 5);
    }

    @Test
    @DisplayName("오늘~6일 뒤 밖의 날짜는 400 ROOM_DATE_OUT_OF_RANGE")
    void outOfRange() {
        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-09")).hasStatusOk();
        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-10"))
                .hasStatus(400)
                .bodyJson().extractingPath("$.code").isEqualTo("ROOM_DATE_OUT_OF_RANGE");
        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-11-02")).hasStatus(400);
    }

    @Test
    @DisplayName("날짜 형식이 틀리면 400 INVALID_REQUEST")
    void badDate() {
        assertThat(mvc.get().uri("/api/rooms/availability?date=2026-13-40"))
                .hasStatus(400)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
    }
}
