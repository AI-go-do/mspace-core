package kr.ac.chosun.mspace.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관리자 기간 막기 (Q-23, core #95). 날짜는 2030-03-05(화), 지금 10:10.
 * 1번 방(3–5명): 대표자 1번 + 2·3번(우리 팀), 대표자 4번 + 5·6번(다른 팀). 7번은 관리자
 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RoomBlockApiTest.FixedClock.class})
class RoomBlockApiTest {

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
        SettableClock settableClock() {
            return new SettableClock();
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SettableClock clock;

    @Autowired
    TransactionOperations tx;

    static Instant at(String time) {
        return OffsetDateTime.parse("2030-03-05T" + time + ":00+09:00").toInstant();
    }

    @BeforeEach
    void setUp() {
        clock.now = at("10:10");
        jdbc.sql("TRUNCATE room_block, notification, reservation, reservation_participant, users RESTART IDENTITY CASCADE")
                .update();
        for (int i = 1; i <= 7; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '회원', now())")
                    .param("e", "b" + i + "@chosun.ac.kr").param("s", "2023100" + i).update();
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", ADMIN).update();
    }

    MvcTestResult block(long user, String body) {
        return mvc.post().uri("/api/admin/room-blocks").header("X-User-Id", String.valueOf(user))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    /** 1번 방을 오늘 start–end 막는다 */
    MvcTestResult block(String start, String end, String reason) {
        return block(ADMIN, """
                {"roomId":1,"startAt":"2030-03-05T%s:00+09:00","endAt":"2030-03-05T%s:00+09:00","reason":"%s",
                 "memo":"  에어컨 점검  "}""".formatted(start, end, reason));
    }

    /** 미리 예약. owner 1이면 2·3번과, 4면 5·6번과 */
    MvcTestResult book(int owner, String start, String end) {
        String others = owner == 1 ? "\"20231002\",\"20231003\"" : "\"20231005\",\"20231006\"";
        return mvc.post().uri("/api/reservations").header("X-User-Id", String.valueOf(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":1,"startAt":"2030-03-05T%s:00+09:00","endAt":"2030-03-05T%s:00+09:00",
                         "participantStudentIds":[%s]}""".formatted(start, end, others))
                .exchange();
    }

    /** 우리 팀이 1번 방을 start–end 쓰는 중 (입실 = 시작) */
    long inUse(String start, String end) {
        return tx.execute(st -> inUseInTx(start, end));
    }

    long inUseInTx(String start, String end) {
        long id = jdbc.sql("""
                        INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                                 checked_in_at)
                        VALUES (1, 1, :s, :e, 3, '123456', 'IN_USE', :s) RETURNING id
                        """)
                .param("s", java.sql.Timestamp.from(at(start))).param("e", java.sql.Timestamp.from(at(end)))
                .query((rs, n) -> rs.getLong(1)).single();
        for (long uid = 1; uid <= 3; uid++) {
            jdbc.sql("""
                    INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                    SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                    """).param("rid", id).param("uid", uid).param("owner", uid == 1).update();
        }
        return id;
    }

    static String responseBody(MvcTestResult r) {
        try {
            return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("관리자가 기간을 막으면 201, 메모는 앞뒤 공백을 떼고 목록에 보인다. 학생은 403")
    void createAndList() {
        assertThat(block(1, """
                {"roomId":1,"startAt":"2030-03-05T12:00:00+09:00","endAt":"2030-03-05T14:00:00+09:00","reason":"RENTAL"}
                """)).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("ADMIN_ONLY");

        MvcTestResult created = block("12:00", "14:00", "INSPECTION");

        assertThat(created).hasStatus(201).bodyJson().extractingPath("$.block.memo").isEqualTo("에어컨 점검");
        assertThat(created).bodyJson().extractingPath("$.block.startAt").isEqualTo("2030-03-05T12:00:00+09:00");
        assertThat(created).bodyJson().extractingPath("$.conflicts").asArray().isEmpty();
        MvcTestResult list = mvc.get().uri("/api/admin/room-blocks").header("X-User-Id", String.valueOf(ADMIN)).exchange();
        assertThat(list).hasStatus(200).bodyJson().extractingPath("$[0].reason").isEqualTo("INSPECTION");
        assertThat(list).bodyJson().extractingPath("$[0].releasedAt").isNull();
    }

    @Test
    @DisplayName("30분 칸이 아니거나 지난 기간·31일 넘게·메모 100자 넘으면 400, 같은 방 막기와 겹치면 409")
    void rejectsBadBlocks() {
        assertThat(block("12:10", "14:00", "OTHER")).hasStatus(400)
                .bodyJson().extractingPath("$.code").isEqualTo("ROOM_BLOCK_INVALID");
        assertThat(block("08:00", "10:00", "OTHER")).hasStatus(400);
        assertThat(block("14:00", "12:00", "OTHER")).hasStatus(400);
        assertThat(block(ADMIN, """
                {"roomId":1,"startAt":"2030-03-05T12:00:00+09:00","endAt":"2030-04-06T12:00:00+09:00","reason":"RENTAL"}
                """)).hasStatus(400);
        assertThat(block(ADMIN, """
                {"roomId":1,"startAt":"2030-03-05T12:00:00+09:00","endAt":"2030-03-05T13:00:00+09:00","reason":"RENTAL",
                 "memo":"%s"}""".formatted("가".repeat(101)))).hasStatus(400);
        assertThat(block(ADMIN, """
                {"roomId":99,"startAt":"2030-03-05T12:00:00+09:00","endAt":"2030-03-05T13:00:00+09:00","reason":"RENTAL"}
                """)).hasStatus(404);
        assertThat(block(ADMIN, """
                {"startAt":"2030-03-05T12:00:00+09:00","endAt":"2030-03-05T13:00:00+09:00","reason":"RENTAL"}
                """)).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("ROOM_BLOCK_INVALID");

        assertThat(block("12:00", "14:00", "INSPECTION")).hasStatus(201);
        assertThat(block("13:30", "15:00", "RENTAL")).hasStatus(409)
                .bodyJson().extractingPath("$.code").isEqualTo("ROOM_BLOCK_OVERLAP");
        assertThat(block("14:00", "15:00", "RENTAL")).hasStatus(201);
    }

    @Test
    @DisplayName("막힌 칸은 미리 예약·즉시 시작이 409 ROOM_BLOCKED이고 사유와 끝 시각을 알려 준다. 풀면 다시 예약된다")
    void blockedSlotsRejectReservations() {
        long blockId = id(mvc.post().uri("/api/admin/room-blocks").header("X-User-Id", String.valueOf(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"roomId":1,"startAt":"2030-03-05T10:00:00+09:00","endAt":"2030-03-05T14:00:00+09:00",
                         "reason":"INSPECTION"}""").exchange(), "$.block.id");

        MvcTestResult booked = book(4, "13:30", "15:00");
        assertThat(booked).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("ROOM_BLOCKED");
        assertThat(booked).bodyJson().extractingPath("$.message").isEqualTo("점검 중이라 3/5 14:00까지 예약할 수 없습니다");
        MvcTestResult instant = mvc.post().uri("/api/reservations/instant").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"roomId":1,"endAt":"2030-03-05T11:00:00+09:00","participantStudentIds":["20231002","20231003"]}""")
                .exchange();
        assertThat(instant).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("ROOM_BLOCKED");
        assertThat(book(4, "14:00", "15:00")).as("막힌 기간 바로 뒤 칸은 된다").hasStatus(201);

        assertThat(mvc.post().uri("/api/admin/room-blocks/" + blockId + "/release")
                .header("X-User-Id", String.valueOf(ADMIN)).exchange())
                .hasStatus(200).bodyJson().extractingPath("$.releasedAt").isEqualTo("2030-03-05T10:10:00+09:00");
        assertThat(book(1, "12:00", "13:00")).as("풀고 나면 예약된다").hasStatus(201);
    }

    @Test
    @DisplayName("사용 중인 방도 다음 칸이 막혀 있으면 연장이 409 ROOM_BLOCKED")
    void blockedSlotRejectsContinue() {
        clock.now = at("10:55");
        long ours = inUse("10:00", "11:00");
        assertThat(block("11:00", "12:00", "CONSTRUCTION")).hasStatus(201);

        MvcTestResult r = mvc.post().uri("/api/reservations/" + ours + "/continue").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"endAt\":\"2030-03-05T11:30:00+09:00\"}").exchange();

        assertThat(r).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("ROOM_BLOCKED");
        assertThat(r).bodyJson().extractingPath("$.message").isEqualTo("공사 중이라 3/5 12:00까지 예약할 수 없습니다");
    }

    @Test
    @DisplayName("막기 전에 잡힌 예약은 목록으로 보이고, 관리자가 취소하면 시작 전 예약만 취소되고 그 팀 세 명에게 알림이 간다")
    void conflictsAreListedThenCanceled() {
        long ours = inUse("10:00", "11:00");
        long theirs = id(book(4, "14:00", "15:00"), "$.id");

        MvcTestResult created = block("10:30", "15:00", "RENTAL");

        assertThat(created).hasStatus(201).bodyJson().extractingPath("$.conflicts[*].reservationId")
                .asArray().containsExactly((int) ours, (int) theirs);
        assertThat(created).bodyJson().extractingPath("$.conflicts[*].cancelable").asArray().containsExactly(false, true);
        assertThat(created).bodyJson().extractingPath("$.conflicts[1].people").isEqualTo(3);
        long blockId = id(created, "$.block.id");

        MvcTestResult canceled = mvc.post().uri("/api/admin/room-blocks/" + blockId + "/cancel-conflicts")
                .header("X-User-Id", String.valueOf(ADMIN)).exchange();

        assertThat(canceled).hasStatus(200).bodyJson().extractingPath("$.canceled").isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", theirs).query(String.class)
                .single()).isEqualTo("CANCELED");
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", ours).query(String.class)
                .single()).as("사용 중인 예약은 그대로 (강제 퇴실은 따로)").isEqualTo("IN_USE");
        assertThat(jdbc.sql("SELECT user_id FROM notification WHERE kind = 'ROOM_BLOCKED' ORDER BY user_id")
                .query(Long.class).list()).containsExactly(4L, 5L, 6L);
        assertThat(mvc.post().uri("/api/admin/room-blocks/" + blockId + "/cancel-conflicts")
                .header("X-User-Id", String.valueOf(ADMIN)).exchange())
                .as("두 번 눌러도 더 취소할 게 없다").bodyJson().extractingPath("$.canceled").isEqualTo(0);
    }

    @Test
    @DisplayName("풀기는 두 번 눌러도 처음 푼 시각 그대로 200, 없는 막기는 404")
    void releaseTwiceAndMissing() {
        long blockId = id(block("12:00", "13:00", "OTHER"), "$.block.id");
        String path = "/api/admin/room-blocks/" + blockId + "/release";
        mvc.post().uri(path).header("X-User-Id", String.valueOf(ADMIN)).exchange();
        clock.now = at("10:40");

        assertThat(mvc.post().uri(path).header("X-User-Id", String.valueOf(ADMIN)).exchange())
                .hasStatus(200).bodyJson().extractingPath("$.releasedAt").isEqualTo("2030-03-05T10:10:00+09:00");
        assertThat(mvc.get().uri("/api/admin/room-blocks/" + blockId + "/conflicts")
                .header("X-User-Id", String.valueOf(ADMIN)).exchange())
                .as("풀린 막기는 겹친 예약 목록이 비어 있다").bodyJson().extractingPath("$").asArray().isEmpty();
        assertThat(mvc.post().uri("/api/admin/room-blocks/999/release").header("X-User-Id", String.valueOf(ADMIN))
                .exchange()).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("ROOM_BLOCK_NOT_FOUND");
    }

    long id(MvcTestResult r, String path) {
        return ((Number) com.jayway.jsonpath.JsonPath.read(responseBody(r), path)).longValue();
    }
}
