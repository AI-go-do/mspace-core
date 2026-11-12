package kr.ac.chosun.mspace.rule;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.common.config.RulesProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionOperations;

/** 이용 규칙 (FR-WEB-003) */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RulesApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RulesProperties props;

    @Autowired
    TransactionOperations tx;

    @Test
    @DisplayName("수용 기준: 로그인 전에도 이용 규칙을 받을 수 있다 (토큰 없이 200)")
    void publicWithoutLogin() {
        assertThat(mvc.get().uri("/api/rules").exchange()).hasStatus(200);
    }

    @Test
    @DisplayName("예약 규칙 숫자는 실제 검사에 쓰는 설정값과 같다 (7일·30분·2시간·입실 10분 전·노쇼 15/10분·연장 1회)")
    void reservationNumbers() {
        MvcTestResult r = mvc.get().uri("/api/rules").exchange();

        var json = assertThat(r).bodyJson();
        json.extractingPath("$.reservation.bookingWindowDays").isEqualTo(7);
        json.extractingPath("$.reservation.slotMinutes").isEqualTo(30);
        json.extractingPath("$.reservation.maxMinutes").isEqualTo(120);
        json.extractingPath("$.reservation.checkinOpenBeforeMinutes").isEqualTo(10);
        json.extractingPath("$.reservation.noShowMinutes").isEqualTo(15);
        json.extractingPath("$.reservation.instantNoShowMinutes").isEqualTo(10);
        json.extractingPath("$.reservation.extension.maxCount").isEqualTo(1);
        json.extractingPath("$.reservation.extension.chainGapMinutes").isEqualTo(30);
    }

    @Test
    @DisplayName("이용 수칙 8개(docs 3.1)와 체크인·퇴실 한 줄 안내가 온다")
    void conductAndReminders() {
        MvcTestResult r = mvc.get().uri("/api/rules").exchange();

        var json = assertThat(r).bodyJson();
        json.extractingPath("$.conduct[*].key").asArray()
                .containsExactly("NOISE", "FOOD", "TIDY", "NO_SHOW", "AWAY", "PHOTO", "DOOR", "BELONGINGS");
        json.extractingPath("$.conduct[1].rule").asString().contains("음식물 반입 금지");
        // 노쇼 숫자는 문구에 박지 않고 설정값(입실 기한·노쇼 벌칙)으로 채운다
        json.extractingPath("$.conduct[3].rule").isEqualTo("시작 15분 안에 키패드로 입실해요(지금 바로 쓰기는 10분)");
        json.extractingPath("$.conduct[3].penalty").asString().contains("30일 안에 2번이면 14일");
        json.extractingPath("$.conduct[7].penalty").asString().contains("3시간 뒤에도 남아 있으면");
        json.extractingPath("$.reminders.checkin").isEqualTo("음식물은 반입 금지예요");
        json.extractingPath("$.reminders.checkout").isEqualTo("자리 정리하셨나요?");
    }

    @Test
    @DisplayName("안내하는 노쇼 분(15·10)이 DB 트리거가 실제로 거는 입실 기한과 같다 (한쪽만 바뀌면 실패)")
    void noShowMatchesTrigger() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES ('r1@chosun.ac.kr', '20249901', '회원', now())")
                .update();
        jdbc.sql("INSERT INTO users (email, student_id, name, verified_at) VALUES ('r2@chosun.ac.kr', '20249902', '회원', now())")
                .update();

        int advance = deadlineMinutes(1, 1, false, "date_trunc('hour', now()) + interval '1 day'");
        int instant = deadlineMinutes(2, 2, true, "now()");

        assertThat(advance).isEqualTo(props.noShowMinutes());
        assertThat(instant).isEqualTo(props.instantNoShowMinutes());
        jdbc.sql("TRUNCATE reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
    }

    /** 인원 확인 트리거는 커밋 때 돌므로, 참여자 없이 넣고 기한만 읽은 뒤 되돌린다 */
    int deadlineMinutes(int roomId, long owner, boolean instant, String start) {
        return tx.execute(status -> {
            status.setRollbackOnly();
            return jdbc.sql("""
                            WITH r AS (
                                INSERT INTO reservation (room_id, owner_id, start_at, end_at, headcount, access_code, is_instant)
                                SELECT :room, :owner, s,
                                       to_timestamp(ceil(extract(epoch FROM s + interval '1 hour') / 1800) * 1800), 3,
                                       '123456', :instant
                                FROM (SELECT %s AS s) x
                                RETURNING start_at, checkin_deadline
                            )
                            SELECT round(extract(epoch FROM checkin_deadline - start_at) / 60)::int FROM r
                            """.formatted(start))
                    .param("room", roomId).param("owner", owner).param("instant", instant)
                    .query(Integer.class).single();
        });
    }
}
