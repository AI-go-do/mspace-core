package kr.ac.chosun.mspace.member;

import static org.assertj.core.api.Assertions.assertThat;

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

/** 참여자 학번 검색 (FR-RSV-004): 학생 인증을 마친 회원만, 이름은 가려서 */
@SpringBootTest(properties = "mspace.auth.dev-header=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberSearchApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void members() {
        jdbc.sql("TRUNCATE reservation, reservation_participant, users RESTART IDENTITY CASCADE").update();
        // 1 홍길동(인증), 2 이몽룡(인증), 3 박수연(인증 전)
        jdbc.sql("""
                INSERT INTO users (email, student_id, name, verified_at) VALUES
                  ('u1@chosun.ac.kr', '20230001', '홍길동', now()),
                  ('u2@chosun.ac.kr', '20230002', '이몽룡', now()),
                  ('u3@chosun.ac.kr', '20230003', '박수연', NULL)
                """).update();
    }

    MvcTestResult search(String studentId) {
        return mvc.get().uri("/api/members?studentId={sid}", studentId).header("X-User-Id", "1").exchange();
    }

    @Test
    @DisplayName("수용 기준: 인증 회원 학번 → 200, 이름은 가린다 (이몽룡 → 이*룡), 이메일·회원 id는 안 나간다")
    void findsVerifiedMember() {
        // given: setUp의 이몽룡(인증)
        // when
        MvcTestResult result = search("20230002");

        // then
        assertThat(result).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.studentId").isEqualTo("20230002");
            json.assertThat().extractingPath("$.name").isEqualTo("이*룡");
            json.assertThat().extractingPath("$.me").isEqualTo(false);
            json.assertThat().doesNotHavePath("$.email");
            json.assertThat().doesNotHavePath("$.id");
        });
    }

    @Test
    @DisplayName("수용 기준: 학생 인증 전 회원과 가입 안 한 학번은 둘 다 404 (MEMBER_NOT_FOUND, 어느 쪽인지 안 알려 줌)")
    void hidesUnverifiedAndUnknown() {
        assertThat(search("20230003")).hasStatus(404).bodyJson()
                .extractingPath("$.code").isEqualTo("MEMBER_NOT_FOUND");
        assertThat(search("29999999")).hasStatus(404).bodyJson()
                .extractingPath("$.code").isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    @DisplayName("학번 없음·빈 값·21자 → 400, 로그인 없음 → 401")
    void rejectsBadRequests() {
        assertThat(mvc.get().uri("/api/members").header("X-User-Id", "1").exchange()).hasStatus(400);
        assertThat(search("  ")).hasStatus(400);
        assertThat(search("1".repeat(21))).hasStatus(400);
        assertThat(mvc.get().uri("/api/members?studentId=20230002").exchange()).hasStatus(401);
    }

    @Test
    @DisplayName("앞뒤 공백은 지우고 찾는다")
    void stripsSpaces() {
        assertThat(search(" 20230001 ")).hasStatusOk().bodyJson()
                .extractingPath("$.name").isEqualTo("홍*동");
    }

    @Test
    @DisplayName("내 학번을 찾으면 me=true (화면이 \"추가\" 대신 \"추가됨\"을 보여 줌), 남은 false. 계정마다 달라 캐시 금지")
    void marksMe() {
        // given: 로그인한 회원은 1번 홍길동(20230001)
        // when
        MvcTestResult mine = search("20230001");
        MvcTestResult other = search("20230002");

        // then
        assertThat(mine).hasStatusOk().hasHeader("Cache-Control", "no-store")
                .bodyJson().extractingPath("$.me").isEqualTo(true);
        assertThat(other).hasStatusOk().bodyJson().extractingPath("$.me").isEqualTo(false);
    }
}
