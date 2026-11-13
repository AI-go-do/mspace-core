package kr.ac.chosun.mspace.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.reservation.application.SweepReservationsUseCase;
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
 * 억울한 노쇼 무르기 정확도 (11/13 지영 "대충 테스트해봐"). *Test가 아니라 평소 빌드에서 빠진다.
 * 실행: ./mvnw test -Dtest=NoShowExcuseEval -Dsurefire.failIfNoSpecifiedTests=false → target/eval/noshow-excuse-eval.md
 *
 * 정답 = "학생이 제시간에 왔는데 장치 고장으로 못 들어갔나"(억울함 → 무르기가 맞음). 사례는 Claude가 만든 가짜.
 * 모든 예약은 2030-03-05(화) 1번 방 10:00–11:00, 번호 유효 09:50–10:15, 노쇼 마감 10:15
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.access.device-token=test-device-token"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, NoShowExcuseEval.MovableClock.class})
class NoShowExcuseEval {

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
    SettableClock clock;

    @Autowired
    SweepReservationsUseCase sweeps;

    int event;

    void now(String time) {
        clock.now = OffsetDateTime.parse("2030-03-05T" + time + ":00+09:00").toInstant();
    }

    void reset() {
        jdbc.sql("""
                TRUNCATE device_fault_attempt, device_fault, user_suspension, notification, reservation,
                         reservation_participant, users RESTART IDENTITY CASCADE
                """).update();
        for (int i = 1; i <= 6; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', :v)")
                    .param("e", "e" + i + "@chosun.ac.kr").param("s", "2023300" + i)
                    .param("v", Timestamp.from(OffsetDateTime.parse("2030-03-01T09:00:00+09:00").toInstant())).update();
        }
    }

    /** owner 팀(1번=1·2·3, 4번=4·5·6)이 room을 start–end 예약. 예약 id */
    long book(int owner, int room, String start, String end) {
        String others = owner == 1 ? "\"20233002\",\"20233003\"" : "\"20233005\",\"20233006\"";
        MvcTestResult r = mvc.post().uri("/api/reservations").header("X-User-Id", String.valueOf(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":%d,"startAt":"2030-03-05T%s:00+09:00","endAt":"2030-03-05T%s:00+09:00",
                         "participantStudentIds":[%s]}""".formatted(room, start, end, others))
                .exchange();
        assertThat(r).hasStatus(201);
        return jdbc.sql("SELECT max(id) FROM reservation").query((rs, n) -> rs.getLong(1)).single();
    }

    long book() {
        now("09:00");
        return book(1, 1, "10:00", "11:00");
    }

    String code(long id) {
        return jdbc.sql("SELECT access_code FROM reservation WHERE id = :id").param("id", id)
                .query((rs, n) -> rs.getString(1)).single();
    }

    String wrong(long id) {
        return code(id).equals("000000") ? "111111" : "000000";
    }

    void fault(int room, String start, String end, String... attempts) {
        List<String> parts = new ArrayList<>();
        for (String a : attempts) {
            String[] p = a.split("@");
            parts.add("{\"at\":\"2030-03-05T%s:00+09:00\",\"code\":\"%s\",\"via\":\"KEYPAD\"}".formatted(p[1], p[0]));
        }
        assertThat(mvc.post().uri("/api/device-faults").header("Authorization", "Bearer test-device-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventId":"eval-%d","roomId":%d,"deviceId":"keypad-%d","kind":"SERVER_UNREACHABLE",
                         "startedAt":"2030-03-05T%s:00+09:00","endedAt":"2030-03-05T%s:00+09:00","attempts":[%s]}"""
                        .formatted(++event, room, room, start, end, String.join(",", parts)))
                .exchange()).hasStatus(200);
    }

    void sweepAt(String time) {
        now(time);
        sweeps.sweep();
    }

    /** 응답과 따로 도는 무르기·1분 정리까지 끝난 뒤 무름 여부 */
    boolean excused(long id) {
        return jdbc.sql("SELECT no_show_excused_at IS NOT NULL FROM reservation WHERE id = :id")
                .param("id", id).query((rs, n) -> rs.getBoolean(1)).single();
    }

    boolean excusedAfterSettle(long id, String sweepTime) {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sweepAt(sweepTime);
        return excused(id);
    }

    record Case(String id, String what, boolean unfair, Supplier<Boolean> run) {}

    List<Case> cases() {
        List<Case> c = new ArrayList<>();
        // ── 억울한 노쇼 (왔는데 장치 고장): 무르는 게 정답
        c.add(new Case("U01", "고장 09:55–10:20, 10:02에 맞는 번호", true, () -> {
            long id = book(); sweepAt("10:20"); fault(1, "09:55", "10:20", code(id) + "@10:02");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("U02", "번호 유효 시작 직후 09:51에 누름", true, () -> {
            long id = book(); sweepAt("10:20"); fault(1, "09:45", "10:20", code(id) + "@09:51");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("U03", "노쇼 마감 직전 10:14에 누름", true, () -> {
            long id = book(); sweepAt("10:20"); fault(1, "10:00", "10:20", code(id) + "@10:14");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("U04", "처음엔 틀린 번호, 다음에 맞는 번호", true, () -> {
            long id = book(); sweepAt("10:20"); fault(1, "10:00", "10:20", wrong(id) + "@10:03", code(id) + "@10:04");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("U05", "같은 번호를 여러 번 누름", true, () -> {
            long id = book(); sweepAt("10:20");
            fault(1, "10:00", "10:20", code(id) + "@10:03", code(id) + "@10:04", code(id) + "@10:06");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("U06", "고장 기록이 노쇼 처리보다 먼저 도착", true, () -> {
            long id = book(); now("10:12"); fault(1, "10:00", "10:10", code(id) + "@10:05");
            return excusedAfterSettle(id, "10:20"); }));
        c.add(new Case("U07", "장치가 2시간 뒤에야 다시 연결돼 기록이 늦게 옴", true, () -> {
            long id = book(); sweepAt("10:20"); now("12:20"); fault(1, "09:55", "12:15", code(id) + "@10:05");
            return excusedAfterSettle(id, "12:21"); }));
        c.add(new Case("U08", "참여자가 대표자 번호를 받아 눌러도 같은 번호", true, () -> {
            long id = book(); sweepAt("10:20"); fault(1, "10:00", "10:20", code(id) + "@10:08");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("U09", "QR로 찍음(키패드 대신)", true, () -> {
            long id = book(); sweepAt("10:20");
            assertThat(mvc.post().uri("/api/device-faults").header("Authorization", "Bearer test-device-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"eventId":"eval-qr-%d","roomId":1,"deviceId":"qr-1","kind":"SERVER_UNREACHABLE",
                             "startedAt":"2030-03-05T10:00:00+09:00","endedAt":"2030-03-05T10:20:00+09:00",
                             "attempts":[{"at":"2030-03-05T10:05:00+09:00","code":"%s","via":"QR"}]}"""
                            .formatted(++event, code(id))).exchange()).hasStatus(200);
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("U10", "문 앞까지 왔지만 번호를 안 누르고 돌아감", true, () -> {
            long id = book(); sweepAt("10:20"); fault(1, "10:00", "10:20");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("U11", "고장 중 틀린 번호만 누름(번호를 잘못 기억)", true, () -> {
            long id = book(); sweepAt("10:20"); fault(1, "10:00", "10:20", wrong(id) + "@10:05");
            return excusedAfterSettle(id, "10:21"); }));
        // ── 진짜 노쇼 (안 왔거나 늦음): 무르지 않는 게 정답
        c.add(new Case("N01", "고장은 있었지만 아무도 안 누름", false, () -> {
            long id = book(); sweepAt("10:20"); fault(1, "10:00", "10:20");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("N02", "고장 없음, 그냥 안 옴", false, () -> {
            long id = book(); sweepAt("10:20");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("N03", "마감 지나 10:20에 맞는 번호(지각)", false, () -> {
            long id = book(); sweepAt("10:25"); fault(1, "10:00", "10:30", code(id) + "@10:20");
            return excusedAfterSettle(id, "10:31"); }));
        c.add(new Case("N04", "다른 방(2번) 장치 고장에 이 번호", false, () -> {
            long id = book(); sweepAt("10:20"); fault(2, "10:00", "10:20", code(id) + "@10:05");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("N05", "앞 팀(08–09시, 노쇼) 번호를 10:05에 누름", false, () -> {
            now("07:00"); long before = book(4, 1, "08:00", "09:00"); String old = code(before);
            sweepAt("08:20"); // 앞 팀은 안 와서 노쇼(번호만 남음)
            long id = book(); sweepAt("10:20"); fault(1, "10:00", "10:20", old + "@10:05");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("N06", "고장이 예약 전(08:00–08:30)에 끝남, 그때 이 번호", false, () -> {
            now("07:50"); long id = book(1, 1, "10:00", "11:00"); sweepAt("10:20");   // 번호가 08:10 전에 있어야 함
            fault(1, "08:00", "08:30", code(id) + "@08:10");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("N07", "번호 유효 전 09:40에만 누르고 돌아감", false, () -> {
            long id = book(); sweepAt("10:20"); fault(1, "09:30", "10:20", code(id) + "@09:40");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("N08", "다른 팀이 같은 시간 2번 방 예약, 1번 방 고장에 그 팀 번호", false, () -> {
            long id = book(); now("09:01"); long other = book(4, 2, "10:00", "11:00");
            sweepAt("10:20"); fault(1, "10:00", "10:20", code(other) + "@10:05");
            // 두 팀 다 안 왔으니 둘 중 하나라도 무르면 틀림 (다른 방 팀을 잘못 무르는 것도 잡음)
            return excusedAfterSettle(id, "10:21") | excused(other); }));
        c.add(new Case("N09", "고장 구간 안에 마감 뒤 10:16에만 누름", false, () -> {
            long id = book(); sweepAt("10:20"); fault(1, "10:00", "10:20", code(id) + "@10:16");
            return excusedAfterSettle(id, "10:21"); }));
        c.add(new Case("N10", "번호를 아무거나 여러 개 눌러 봄", false, () -> {
            long id = book(); sweepAt("10:20");
            fault(1, "10:00", "10:20", wrong(id) + "@10:03", "123456@10:04", "654321@10:05");
            return excusedAfterSettle(id, "10:21"); }));
        return c;
    }

    @Test
    void measure() throws Exception {
        List<String> rows = new ArrayList<>();
        int right = 0;
        int unfairMissed = 0;
        int wrongExcuse = 0;
        int errors = 0;   // 사례 실행이 실패한 것. 맞음·틀림 어느 쪽에도 안 셈
        List<Case> all = cases();
        for (Case k : all) {
            reset();
            event = 0;
            boolean excused;
            String err = "";
            try {
                excused = k.run().get();
            } catch (Throwable t) {
                excused = false;
                err = " (오류: " + t.getClass().getSimpleName() + ")";
            }
            boolean ok = err.isEmpty() && excused == k.unfair();
            if (!err.isEmpty()) {
                errors++;
            } else if (ok) {
                right++;
            } else if (k.unfair()) {
                unfairMissed++;
            } else {
                wrongExcuse++;
            }
            rows.add("| %s | %s | %s | %s | %s |".formatted(k.id(), k.what(), k.unfair() ? "무름" : "안 무름",
                    err.isEmpty() ? (excused ? "무름" : "안 무름") : err.strip(), err.isEmpty() ? (ok ? "맞음" : "틀림") : "오류"));
        }
        StringBuilder md = new StringBuilder();
        md.append("# 억울한 노쇼 무르기 정확도 (가짜 사례 ").append(all.size()).append("개)\n\n");
        md.append("맞음 %d/%d (%.1f%%) · 억울한데 못 무름 %d · 진짜 노쇼를 무름 %d%s\n\n"
                .formatted(right, all.size(), right * 100.0 / all.size(), unfairMissed, wrongExcuse,
                        errors > 0 ? " · 실행 오류 " + errors + " (다시 재야 함)" : ""));
        md.append("| 사례 | 상황 | 정답 | core 결과 | 판정 |\n|---|---|---|---|---|\n");
        rows.forEach(r -> md.append(r).append('\n'));
        Path out = Path.of("target/eval/noshow-excuse-eval.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        System.out.println(md);
    }
}
