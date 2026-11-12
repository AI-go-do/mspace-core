package kr.ac.chosun.mspace.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.report.application.AutoJudgeUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import kr.ac.chosun.mspace.report.application.ReportJudge;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 신고 자동 판정 (FR-RPT-103 10차, 규칙 판정기 report-rules-v1).
 * 확신도 기준을 0.9로 올려 "판정기는 사실, 확신도 낮아 확인 불가"도 같이 본다.
 */
@SpringBootTest(properties = {"mspace.auth.dev-header=true", "mspace.report.judge.min-confidence=0.9",
        "mspace.tidy.camera-warn=true"})
@Import(TestcontainersConfiguration.class)
class AutoJudgeTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AutoJudgeUseCase judge;

    @Autowired
    TransactionOperations tx;

    long previous;
    long mine;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE report_judgement, retrieve_pass, warning_appeal, tidy_check, notification, report_suspension, user_suspension,
                         user_warning, report, reservation_photo, reservation_participant, reservation, users
                         RESTART IDENTITY CASCADE
                """).update();
        for (int i = 1; i <= 6; i++) {
            jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES (:e, :s, '홍길동', now())")
                    .param("e", "j" + i + "@chosun.ac.kr").param("s", "202400" + (60 + i)).update();
        }
        // 앞 팀(대표자 1): 5시간 전 입실, 4시간 전 퇴실(찾으러 올 3시간 지남) / 신고한 팀(대표자 4): 1시간 전 입실, 사용 중
        previous = reservation(1, "5 hours", "4 hours", "CHECKED_OUT", "'4 hours'");
        mine = reservation(4, "1 hour", "-1 hour", "IN_USE", null);
    }

    private long reservation(long owner, String startAgo, String endAgo, String status, String outAgo) {
        String out = outAgo == null ? "NULL" : "t - interval " + outAgo;
        return tx.execute(st -> {
            long id = jdbc.sql("""
                    INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, status,
                                             checked_in_at, checked_out_at)
                    SELECT 1, :owner, t - interval '%s', t - interval '%s', 3, :code, '%s', t - interval '%s', %s
                    FROM (SELECT date_trunc('hour', now()) AS t) x
                    RETURNING id
                    """.formatted(startAgo, endAgo, status, startAgo, out))
                    .param("owner", owner).param("code", "00000" + owner).query(Long.class).single();
            for (long uid = owner; uid < owner + 3; uid++) {
                jdbc.sql("""
                                INSERT INTO reservation_participant (reservation_id, user_id, is_owner, start_at, end_at)
                                SELECT :rid, :uid, :owner, start_at, end_at FROM reservation WHERE id = :rid
                                """).param("rid", id).param("uid", uid).param("owner", uid == owner).update();
            }
            return id;
        });
    }

    private void cameraSaw(String result, String items, String confidences) {
        jdbc.sql("""
                INSERT INTO tidy_check (event_id, reservation_id, room_id, device_id, phase, result, items, confidences,
                                        left_items, observed_at, received_at)
                VALUES ('ev-first', :rid, 1, 'camera-1', 'FIRST', :result, CAST(:items AS text[]),
                        CAST(:conf AS real[]), '{}', now() - interval '2 hours', now() - interval '2 hours')
                """).param("rid", previous).param("result", result).param("items", items)
                .param("conf", confidences).update();
    }

    private long report(String category, String ago) {
        return jdbc.sql("""
                INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category, content, created_at)
                VALUES (1, 4, :mine, :category, '방이 어질러져 있어요', now() - interval '%s') RETURNING id
                """.formatted(ago)).param("mine", mine).param("category", category).query(Long.class).single();
    }

    private String verdict(long report) {
        return jdbc.sql("SELECT verdict FROM report WHERE id = :id").param("id", report).query(String.class).single();
    }

    private int warnings(long user) {
        return jdbc.sql("SELECT count(*) FROM user_warning WHERE user_id = :u").param("u", user)
                .query(Integer.class).single();
    }

    @Test
    @DisplayName("어질러짐: 앞 팀 퇴실 직후 카메라가 물건을 0.95로 봤으면 사실, 앞 팀 대표자에게 경고하고 판정 기록을 남긴다")
    void messyConfirmedByCamera() {
        // given
        cameraSaw("LEFT_ITEMS", "{cup,bottle}", "{0.95,0.4}");
        long id = report("MESSY", "10 minutes");

        // when
        int done = judge.judgeOpen();

        // then
        assertThat(done).isEqualTo(1);
        assertThat(verdict(id)).isEqualTo("CONFIRMED");
        assertThat(warnings(1)).isEqualTo(1);
        assertThat(warnings(4)).isZero();
        var row = jdbc.sql("SELECT judge, prompt_version, verdict, applied, confidence FROM report_judgement")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getFloat(5)}).single();
        assertThat(row).containsExactly("rules", "report-rules-v1", "CONFIRMED", "CONFIRMED", 0.95f);
        assertThat(jdbc.sql("SELECT judged_by IS NULL FROM report").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE user_id = 4 AND kind = 'REPORT_RESULT'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("판정기가 사실이라 해도 확신도 0.85가 기준 0.9보다 낮으면 확인 불가, 아무도 경고받지 않는다")
    void lowConfidenceIsUnverified() {
        // given
        cameraSaw("LEFT_ITEMS", "{cup}", "{0.85}");
        long id = report("MESSY", "10 minutes");

        // when
        judge.judgeOpen();

        // then
        assertThat(verdict(id)).isEqualTo("UNVERIFIED");
        assertThat(warnings(1)).isZero();
        assertThat(jdbc.sql("SELECT verdict || '>' || applied FROM report_judgement").query(String.class).single())
                .isEqualTo("CONFIRMED>UNVERIFIED");
    }

    @Test
    @DisplayName("카메라가 깨끗하다고 봤어도 허위로 정하지 않고 확인 불가. 신고자도 경고받지 않는다")
    void cleanCameraIsNotFalse() {
        // given
        cameraSaw("CLEAN", "{}", "{}");
        long id = report("MESSY", "10 minutes");

        // when
        judge.judgeOpen();

        // then
        assertThat(verdict(id)).isEqualTo("UNVERIFIED");
        assertThat(warnings(4)).isZero();
    }

    @Test
    @DisplayName("비품 고장은 자동으로 볼 근거가 없어 확인 불가")
    void brokenIsUnverified() {
        // given
        long id = report("BROKEN", "10 minutes");

        // when
        judge.judgeOpen();

        // then
        assertThat(verdict(id)).isEqualTo("UNVERIFIED");
        assertThat(warnings(1)).isZero();
    }

    @Test
    @DisplayName("신고 5분이 안 됐으면, 소음이면, 관리자가 이미 판정했으면 건드리지 않는다")
    void skipsTooNewNoiseAndJudged() {
        // given
        cameraSaw("LEFT_ITEMS", "{cup}", "{0.95}");
        long fresh = report("MESSY", "1 minute");
        long noise = report("NOISE", "10 minutes");
        long judged = report("WINDOW_OPEN", "10 minutes");
        jdbc.sql("UPDATE report SET verdict = 'CONFIRMED', judged_at = now() WHERE id = :id").param("id", judged).update();

        // when
        int done = judge.judgeOpen();

        // then
        assertThat(done).isZero();
        assertThat(verdict(fresh)).isEqualTo("OPEN");
        assertThat(verdict(noise)).isEqualTo("OPEN");
        assertThat(jdbc.sql("SELECT count(*) FROM report_judgement").query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("앞 팀이 3시간 뒤 다시 확인으로 이미 카메라 경고를 받았으면 같은 물건으로 학생 신고가 와도 확인 불가, 경고는 1번")
    void alreadyWarnedByCameraNotTwice() {
        // given: 다시 확인에서 core가 카메라 신고를 만들어 앞 팀 대표자를 이미 경고함
        cameraSaw("LEFT_ITEMS", "{cup}", "{0.95}");
        long camera = jdbc.sql("""
                INSERT INTO report (room_id, source, target_reservation_id, category, content, verdict, judged_at)
                VALUES (1, 'CAMERA', :prev, 'MESSY', 'cup(0.95)', 'CONFIRMED', now()) RETURNING id
                """).param("prev", previous).query(Long.class).single();
        jdbc.sql("INSERT INTO user_warning (user_id, report_id, reservation_id, reason) VALUES (1, :r, :prev, 'VIOLATION')")
                .param("r", camera).param("prev", previous).update();
        long id = report("MESSY", "10 minutes");

        // when
        judge.judgeOpen();

        // then
        assertThat(verdict(id)).isEqualTo("UNVERIFIED");
        assertThat(warnings(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("앞 팀이 찾으러 갈게요 QR로 문을 열었으면 퇴실 직후 카메라가 물건을 봤어도 확인 불가")
    void retrievedIsUnverified() {
        // given
        cameraSaw("LEFT_ITEMS", "{cup}", "{0.95}");
        jdbc.sql("""
                INSERT INTO retrieve_pass (reservation_id, room_id, issued_by, code, issued_at, expires_at, first_used_at)
                VALUES (:prev, 1, 1, '123456', now() - interval '100 minutes', now() - interval '90 minutes',
                        now() - interval '95 minutes')
                """).param("prev", previous).update();
        long id = report("MESSY", "10 minutes");

        // when
        judge.judgeOpen();

        // then
        assertThat(verdict(id)).isEqualTo("UNVERIFIED");
        assertThat(warnings(1)).isZero();
    }

    @Test
    @DisplayName("다시 확인(RECHECK)이 깨끗했으면 퇴실 직후 물건을 봤어도 그 뒤 결과를 따라 확인 불가")
    void recheckCleanWins() {
        // given
        cameraSaw("LEFT_ITEMS", "{cup}", "{0.95}");
        jdbc.sql("""
                INSERT INTO tidy_check (event_id, reservation_id, room_id, device_id, phase, result, items, confidences,
                                        left_items, observed_at, received_at)
                VALUES ('ev-re', :rid, 1, 'camera-1', 'RECHECK', 'CLEAN', '{}', '{}', '{}',
                        now() - interval '20 minutes', now() - interval '20 minutes')
                """).param("rid", previous).update();
        long id = report("MESSY", "10 minutes");

        // when
        judge.judgeOpen();

        // then
        assertThat(verdict(id)).isEqualTo("UNVERIFIED");
        assertThat(warnings(1)).isZero();
    }

    @Test
    @DisplayName("어질러짐 신고는 앞 팀 퇴실 3시간(찾으러 올 시간)이 지나야 판정한다. 그 전에는 대기로 둔다 (11/11 지영)")
    void messyWaitsForRetrieveWindow() {
        // given: 앞 팀이 1시간 전에 퇴실
        jdbc.sql("UPDATE reservation SET checked_out_at = now() - interval '1 hour' WHERE id = :id")
                .param("id", previous).update();
        cameraSaw("LEFT_ITEMS", "{cup}", "{0.95}");
        long messy = report("MESSY", "10 minutes");
        long broken = report("BROKEN", "10 minutes");

        // when
        int done = judge.judgeOpen();

        // then: 비품 고장은 기다리지 않는다
        assertThat(done).isEqualTo(1);
        assertThat(verdict(messy)).isEqualTo("OPEN");
        assertThat(verdict(broken)).isEqualTo("UNVERIFIED");
        assertThat(warnings(1)).isZero();
    }

    @Test
    @DisplayName("퇴실 3시간 뒤라도 마지막 QR(10분)이 닫히기 전에는 어질러짐 신고를 판정하지 않는다 (CodeRabbit)")
    void messyWaitsForLastPass() {
        // given: 앞 팀이 3시간 5분 전에 퇴실. 2시간 59분에 받은 QR이면 아직 열려 있을 수 있다
        jdbc.sql("UPDATE reservation SET checked_out_at = now() - interval '185 minutes' WHERE id = :id")
                .param("id", previous).update();
        cameraSaw("LEFT_ITEMS", "{cup}", "{0.95}");
        long messy = report("MESSY", "10 minutes");

        // when
        judge.judgeOpen();

        // then
        assertThat(verdict(messy)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("신고자보다 늦게 입실한 예약의 카메라 결과로는 사실로 정하지 않는다 (CodeRabbit)")
    void laterReservationNotUsed() {
        // given: 앞 팀으로 잡히는 예약이 신고자보다 늦게 입실함(다음 예약 조기 입실)
        jdbc.sql("UPDATE reservation SET checked_in_at = now() - interval '30 minutes' WHERE id = :id")
                .param("id", previous).update();
        cameraSaw("LEFT_ITEMS", "{cup}", "{0.95}");
        long id = report("MESSY", "10 minutes");

        // when
        judge.judgeOpen();

        // then
        assertThat(verdict(id)).isEqualTo("UNVERIFIED");
        assertThat(warnings(1)).isZero();
    }

    /**
     * 판정기가 사실을 읽은 뒤, 적용 전에 정리 다시 확인(RECHECK)이 같은 예약에 경고한 경우 (CodeRabbit #118).
     * 판정기 호출 중에 경고를 넣어 그 순서를 만든다
     */
    @Nested
    @Import(RecheckWarnsMeanwhile.Config.class)
    class RecheckWarnsMeanwhile {

        static final java.util.concurrent.atomic.AtomicReference<Runnable> MEANWHILE = new java.util.concurrent.atomic.AtomicReference<>();

        @TestConfiguration
        static class Config {
            @Bean
            @Primary
            ReportJudge meanwhileJudge(kr.ac.chosun.mspace.common.config.TidyPolicy tidy) {
                ReportJudge rules = new kr.ac.chosun.mspace.report.adapter.out.judge.RuleReportJudgeAccess(tidy).judge();
                return c -> {
                    ReportJudge.Judgement j = rules.judge(c);
                    Runnable r = MEANWHILE.getAndSet(null);
                    if (r != null) {
                        r.run();
                    }
                    return j;
                };
            }
        }

        @Test
        @DisplayName("판정기가 읽은 뒤 다시 확인이 먼저 경고했으면 적용할 때 잠그고 다시 봐서 확인 불가, 경고는 1번")
        void secondWarningBlockedUnderLock() {
            // given
            cameraSaw("LEFT_ITEMS", "{cup}", "{0.95}");
            long id = report("MESSY", "10 minutes");
            MEANWHILE.set(() -> {
                long camera = jdbc.sql("""
                        INSERT INTO report (room_id, source, target_reservation_id, category, content, verdict, judged_at)
                        VALUES (1, 'CAMERA', :prev, 'MESSY', 'cup(0.95)', 'CONFIRMED', now()) RETURNING id
                        """).param("prev", previous).query(Long.class).single();
                jdbc.sql("INSERT INTO user_warning (user_id, report_id, reservation_id, reason) VALUES (1, :r, :prev, 'VIOLATION')")
                        .param("r", camera).param("prev", previous).update();
            });

            // when
            judge.judgeOpen();

            // then
            assertThat(verdict(id)).isEqualTo("UNVERIFIED");
            assertThat(warnings(1)).isEqualTo(1);
            assertThat(jdbc.sql("SELECT verdict || '/' || applied FROM report_judgement").query(String.class).single())
                    .isEqualTo("CONFIRMED/UNVERIFIED");
        }
    }

    @Nested
    @Import(RecheckWarnsMeanwhile.Config.class)
    class RecheckArrivesMeanwhile {

        @Test
        @DisplayName("판정기가 퇴실 직후 결과만 읽은 뒤 다시 확인(RECHECK)이 깨끗하게 들어오면 옛 결과로 경고하지 않는다")
        void staleFirstNotUsed() {
            // given
            cameraSaw("LEFT_ITEMS", "{cup}", "{0.95}");
            long id = report("MESSY", "10 minutes");
            RecheckWarnsMeanwhile.MEANWHILE.set(() -> jdbc.sql("""
                    INSERT INTO tidy_check (event_id, reservation_id, room_id, device_id, phase, result, items, confidences,
                                            left_items, observed_at, received_at)
                    VALUES ('ev-late', :rid, 1, 'camera-1', 'RECHECK', 'CLEAN', '{}', '{}', '{}', now(), now())
                    """).param("rid", previous).update());

            // when
            judge.judgeOpen();

            // then
            assertThat(verdict(id)).isEqualTo("UNVERIFIED");
            assertThat(warnings(1)).isZero();
        }
    }

    /**
     * 창문 열림 (11/12 지영 "창문 열림이랑 어질러짐"): 판정기에 앞 팀 퇴실 앞뒤 방 CO2·온도를 넘긴다.
     * 판정기가 허위라고 해도 apply-false가 꺼져 있으면 기록에만 허위, 실제는 확인 불가
     */
    @Nested
    @Import(WindowOpen.Config.class)
    class WindowOpen {

        static final java.util.concurrent.atomic.AtomicReference<ReportJudge.Case> SEEN = new java.util.concurrent.atomic.AtomicReference<>();

        @TestConfiguration
        static class Config {
            @Bean
            @Primary
            ReportJudge falseJudge() {
                return c -> {
                    SEEN.set(c);
                    return new ReportJudge.Judgement(kr.ac.chosun.mspace.report.domain.ReportVerdict.FALSE, 0.95,
                            List.of("창문이 닫혀 있었어요."), List.of("SENSOR"), "test", "test-v1");
                };
            }
        }

        void air(String id, int room, String at, int ppm, String temp) {
            jdbc.sql("""
                    INSERT INTO sensor_event (event_id, room_id, device_id, kind, ppm, temperature_c, observed_at, received_at)
                    VALUES (:id, :room, 'air-1', 'CO2', :ppm, CAST(:temp AS numeric), %s, now())
                    """.formatted(at)).param("id", id).param("room", room).param("ppm", ppm).param("temp", temp).update();
        }

        @BeforeEach
        void leftForty() {
            // 앞 팀은 40분 전에 퇴실, 신고는 10분 전 → 신고는 퇴실 30분 뒤
            jdbc.sql("UPDATE reservation SET checked_out_at = now() - interval '40 minutes' WHERE id = :id")
                    .param("id", previous).update();
            jdbc.sql("TRUNCATE sensor_event").update();
            SEEN.set(null);
        }

        @Test
        @DisplayName("창문 열림 신고: 퇴실 15분 전부터 신고 때까지 그 방 CO2·온도를 5분 평균으로 넘긴다 (다른 방·신고 뒤 값은 빼고)")
        void roomAirGiven() {
            // given
            long id = jdbc.sql("""
                    INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category, content, created_at)
                    VALUES (1, 4, :mine, 'WINDOW_OPEN', '창문이 열려 있어요', now() - interval '10 minutes') RETURNING id
                    """).param("mine", mine).query(Long.class).single();
            air("a0", 1, "now() - interval '60 minutes'", 1500, "24.0");   // 퇴실 20분 전: 15분보다 앞이라 뺌
            air("a1", 1, "now() - interval '44 minutes'", 1200, "23.4");   // -4분 → -5 묶음
            air("a2", 1, "now() - interval '42 minutes'", 1100, "23.6");   // -2분 → -5 묶음
            air("a3", 1, "now() - interval '33 minutes'", 600, null);      // 7분 → 5 묶음
            air("a4", 1, "now() - interval '23 minutes'", 450, "19.5");    // 17분 → 15 묶음
            air("a5", 1, "now() - interval '5 minutes'", 900, "20.0");     // 신고 뒤 → 뺌
            air("b1", 2, "now() - interval '30 minutes'", 2000, "25.0");   // 다른 방 → 뺌

            // when
            judge.judgeOpen();

            // then
            ReportJudge.Case c = SEEN.get();
            assertThat(c.reportId()).isEqualTo(id);
            assertThat(c.roomAir().reportMinute()).isEqualTo(30);
            assertThat(c.roomAir().points()).containsExactly(
                    new ReportJudge.AirPoint(-5, 1150, 23.5),
                    new ReportJudge.AirPoint(5, 600, null),
                    new ReportJudge.AirPoint(15, 450, 19.5));
        }

        @Test
        @DisplayName("창문 열림 신고: 앞 팀이 신고자보다 늦게 입실했으면 센서 값을 넘기지 않는다 (CodeRabbit)")
        void roomAirSkippedWhenPreviousCameLater() {
            // given: 앞 팀으로 잡히는 예약이 신고자(1시간 넘게 전 입실)보다 늦게, 50분 전 입실해 40분 전 퇴실
            jdbc.sql("UPDATE reservation SET checked_in_at = now() - interval '50 minutes' WHERE id = :id")
                    .param("id", previous).update();
            report("WINDOW_OPEN", "10 minutes");
            air("a1", 1, "now() - interval '44 minutes'", 1200, "23.4");
            air("a2", 1, "now() - interval '33 minutes'", 600, "23.0");
            air("a3", 1, "now() - interval '23 minutes'", 450, "19.5");

            // when
            judge.judgeOpen();

            // then
            assertThat(SEEN.get().roomAir()).isNull();
        }

        @Test
        @DisplayName("판정기가 허위라 해도 apply-false가 꺼져 있으면 확인 불가, 신고자는 경고받지 않고 기록에는 허위로 남는다")
        void falseOnlyRecorded() {
            // given
            long id = report("WINDOW_OPEN", "10 minutes");

            // when
            judge.judgeOpen();

            // then
            assertThat(verdict(id)).isEqualTo("UNVERIFIED");
            assertThat(warnings(4)).isZero();
            assertThat(jdbc.sql("SELECT verdict || '/' || applied FROM report_judgement").query(String.class).single())
                    .isEqualTo("FALSE/UNVERIFIED");
        }

        @Test
        @DisplayName("어질러짐 신고는 센서 값을 넘기지 않는다")
        void messyHasNoAir() {
            // given
            report("MESSY", "10 minutes");
            jdbc.sql("UPDATE reservation SET checked_out_at = now() - interval '4 hours' WHERE id = :id")
                    .param("id", previous).update();

            // when
            judge.judgeOpen();

            // then
            assertThat(SEEN.get().roomAir()).isNull();
        }

        @Nested
        @TestPropertySource(properties = "mspace.report.judge.apply-false=true")
        class ApplyFalseOn {

            @Test
            @DisplayName("apply-false를 켜면 허위가 그대로 적용돼 신고자가 경고를 받는다")
            void falseApplied() {
                // given
                long id = report("WINDOW_OPEN", "10 minutes");

                // when
                judge.judgeOpen();

                // then
                assertThat(verdict(id)).isEqualTo("FALSE");
                assertThat(warnings(4)).isEqualTo(1);
            }
        }
    }

    /** 창문 열림 사실 (11/12 재웅 리뷰): 기준 숫자가 추측이라 apply-window-confirmed가 꺼져 있으면 기록에만 사실 */
    @Nested
    @Import(WindowConfirmed.Config.class)
    class WindowConfirmed {

        @TestConfiguration
        static class Config {
            @Bean
            @Primary
            ReportJudge confirmedJudge() {
                return c -> new ReportJudge.Judgement(kr.ac.chosun.mspace.report.domain.ReportVerdict.CONFIRMED, 0.95,
                        List.of("퇴실 뒤 CO2가 바깥 수준으로 떨어졌어요."), List.of("SENSOR"), "test", "test-v1");
            }
        }

        @Test
        @DisplayName("창문 열림을 판정기가 사실이라 해도 apply-window-confirmed가 꺼져 있으면 확인 불가, 앞 팀은 경고받지 않고 기록에는 사실로 남는다")
        void confirmedOnlyRecorded() {
            // given
            long id = report("WINDOW_OPEN", "10 minutes");

            // when
            judge.judgeOpen();

            // then
            assertThat(verdict(id)).isEqualTo("UNVERIFIED");
            assertThat(warnings(1)).isZero();
            assertThat(jdbc.sql("SELECT verdict || '/' || applied FROM report_judgement").query(String.class).single())
                    .isEqualTo("CONFIRMED/UNVERIFIED");
        }

        @Nested
        @TestPropertySource(properties = "mspace.report.judge.apply-window-confirmed=true")
        class On {

            @Test
            @DisplayName("apply-window-confirmed를 켜면 창문 열림 사실이 그대로 적용돼 앞 팀 대표자가 경고를 받는다")
            void confirmedApplied() {
                // given
                long id = report("WINDOW_OPEN", "10 minutes");

                // when
                judge.judgeOpen();

                // then
                assertThat(verdict(id)).isEqualTo("CONFIRMED");
                assertThat(warnings(1)).isEqualTo(1);
            }
        }
    }

    /** 11/11 지영: 카메라 정확도 51%라 카메라만으로는 사실로 정하지 않는다 (기본값 camera-warn=false) */
    @Nested
    @TestPropertySource(properties = "mspace.tidy.camera-warn=false")
    class CameraWarnOff {

        @Test
        @DisplayName("카메라 경고가 꺼져 있으면 앞 팀 카메라가 0.95로 봤어도 어질러짐 신고는 확인 불가, 경고 없음")
        void messyUnverified() {
            // given
            cameraSaw("LEFT_ITEMS", "{cup}", "{0.95}");
            long id = report("MESSY", "10 minutes");

            // when
            judge.judgeOpen();

            // then
            assertThat(verdict(id)).isEqualTo("UNVERIFIED");
            assertThat(warnings(1)).isZero();
        }
    }
}
