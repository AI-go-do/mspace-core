package kr.ac.chosun.mspace.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import kr.ac.chosun.mspace.TestcontainersConfiguration;
import kr.ac.chosun.mspace.common.auth.JwtTokens;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.member.application.MemberPort;
import kr.ac.chosun.mspace.member.application.VerificationCodeSender;
import org.junit.jupiter.api.AfterEach;
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

/**
 * FR-AUTH-001 학교 메일 로그인. 지금 = 2026-11-03 10:00 서울에서 시작하고, 테스트 안에서 시계를 앞으로 돌린다.
 * 서비스가 "틀린 횟수"를 바로 저장하므로(롤백 안 됨) 테스트 롤백 대신 테이블을 비운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, EmailLoginApiTest.Fakes.class})
class EmailLoginApiTest {

    static final Instant START = Instant.parse("2026-11-03T01:00:00Z");
    static final String EMAIL = "hong@chosun.ac.kr";

    /** 앞으로 돌릴 수 있는 시계 */
    static class MovableClock extends Clock {
        Instant now = START;

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

        void plus(Duration d) {
            now = now.plus(d);
        }
    }

    /** 보낸 번호를 기억하는 가짜 메일 */
    static class CapturingSender implements VerificationCodeSender {
        final Map<String, String> last = new java.util.concurrent.ConcurrentHashMap<>();
        boolean broken;

        @Override
        public void send(String email, String code) {
            if (broken) {
                throw new IllegalStateException("SMTP 연결 실패 (테스트)");
            }
            last.put(email, code);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }

        @Bean
        @Primary
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    kr.ac.chosun.mspace.member.application.EmailVerificationPort verifications;

    @Autowired
    MovableClock clock;

    @Autowired
    CapturingSender mail;

    @Autowired
    JwtTokens tokens;

    @Autowired
    MemberPort members;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE email_verification, users RESTART IDENTITY CASCADE").update();
        clock.now = START;
        mail.last.clear();
        mail.broken = false;
    }

    MvcTestResult sendCode(String email) {
        return mvc.post().uri("/api/auth/email-codes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\"}".formatted(email)).exchange();
    }

    MvcTestResult login(String email, String code, String name, String studentId) {
        String body = name == null
                ? "{\"email\": \"%s\", \"code\": \"%s\"}".formatted(email, code)
                : "{\"email\": \"%s\", \"code\": \"%s\", \"name\": \"%s\", \"studentId\": \"%s\"}"
                        .formatted(email, code, name, studentId);
        return mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    @Test
    @DisplayName("처음 로그인: 번호 받고 이름·학번과 함께 보내면 가입되고 토큰이 나온다")
    void firstLoginSignsUp() throws Exception {
        assertThat(sendCode(EMAIL)).hasStatus(204);
        String code = mail.last.get(EMAIL);
        assertThat(code).matches("\\d{6}");

        MvcTestResult result = login(EMAIL, code, "홍길동", "20230001");
        assertThat(result).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.tokenType").isEqualTo("Bearer");
            json.assertThat().extractingPath("$.newMember").isEqualTo(true);
            json.assertThat().extractingPath("$.memberId").isEqualTo(1);
        });
        String token = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.accessToken");
        assertThat(tokens.verify(token)).hasValueSatisfying(v -> {
            assertThat(v.memberId()).isEqualTo(1L);
            assertThat(v.scopes()).containsExactly("user");
        });
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = :e AND verified_at IS NOT NULL")
                .param("e", EMAIL).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("두 번째 로그인: 이름·학번 없이 번호만으로 같은 회원 토큰")
    void returningMember() {
        sendCode(EMAIL);
        login(EMAIL, mail.last.get(EMAIL), "홍길동", "20230001");
        clock.plus(Duration.ofMinutes(2));

        sendCode(" Hong@Chosun.ac.kr ");   // 대소문자·공백이 달라도 같은 사람
        assertThat(login(EMAIL, mail.last.get(EMAIL), null, null)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.memberId").isEqualTo(1);
            json.assertThat().extractingPath("$.newMember").isEqualTo(false);
        });
    }

    @Test
    @DisplayName("수용 기준: 학교 메일이 아니면 번호를 보내지 않는다")
    void schoolEmailOnly() {
        assertThat(sendCode("hong@gmail.com")).hasStatus(400).bodyJson()
                .extractingPath("$.code").isEqualTo("SCHOOL_EMAIL_ONLY");
        assertThat(mail.last).isEmpty();
    }

    @Test
    @DisplayName("1분 안에 다시 받으면 429, 1분 지나면 된다")
    void resendInterval() {
        sendCode(EMAIL);
        clock.plus(Duration.ofSeconds(59));
        assertThat(sendCode(EMAIL)).hasStatus(429).bodyJson()
                .extractingPath("$.code").isEqualTo("EMAIL_CODE_TOO_SOON");
        clock.plus(Duration.ofSeconds(1));
        assertThat(sendCode(EMAIL)).hasStatus(204);
    }

    @Test
    @DisplayName("수용 기준: 10분이 지난 번호는 거절")
    void expired() {
        sendCode(EMAIL);
        String code = mail.last.get(EMAIL);
        clock.plus(Duration.ofMinutes(10));
        assertThat(login(EMAIL, code, "홍길동", "20230001")).hasStatus(400).bodyJson()
                .extractingPath("$.code").isEqualTo("EMAIL_CODE_EXPIRED");
    }

    @Test
    @DisplayName("수용 기준: 5번 틀리면 맞는 번호도 거절, 새 번호를 받으면 다시 된다")
    void fiveWrongAttempts() {
        sendCode(EMAIL);
        String code = mail.last.get(EMAIL);
        for (int i = 0; i < 5; i++) {
            assertThat(login(EMAIL, wrong(code), "홍길동", "20230001")).hasStatus(400).bodyJson()
                    .extractingPath("$.code").isEqualTo("EMAIL_CODE_MISMATCH");
        }
        assertThat(login(EMAIL, code, "홍길동", "20230001")).hasStatus(400).bodyJson()
                .extractingPath("$.code").isEqualTo("EMAIL_CODE_TOO_MANY_ATTEMPTS");

        clock.plus(Duration.ofMinutes(1));
        sendCode(EMAIL);
        assertThat(login(EMAIL, mail.last.get(EMAIL), "홍길동", "20230001")).hasStatusOk();
    }

    @Test
    @DisplayName("한 번 쓴 번호는 다시 못 쓴다")
    void codeIsOneTime() {
        sendCode(EMAIL);
        String code = mail.last.get(EMAIL);
        assertThat(login(EMAIL, code, "홍길동", "20230001")).hasStatusOk();
        assertThat(login(EMAIL, code, null, null)).hasStatus(400).bodyJson()
                .extractingPath("$.code").isEqualTo("EMAIL_CODE_NOT_FOUND");
    }

    @Test
    @DisplayName("번호를 받은 적 없으면 400")
    void noCode() {
        assertThat(login(EMAIL, "123456", "홍길동", "20230001")).hasStatus(400).bodyJson()
                .extractingPath("$.code").isEqualTo("EMAIL_CODE_NOT_FOUND");
    }

    @Test
    @DisplayName("처음인데 이름·학번이 없으면 400, 번호는 살아 있어서 채워 보내면 된다")
    void signupInfoRequired() {
        sendCode(EMAIL);
        String code = mail.last.get(EMAIL);
        assertThat(login(EMAIL, code, null, null)).hasStatus(400).bodyJson()
                .extractingPath("$.code").isEqualTo("SIGNUP_INFO_REQUIRED");
        assertThat(login(EMAIL, code, "홍길동", "2023-0001")).hasStatus(400).bodyJson()
                .extractingPath("$.code").isEqualTo("INVALID_SIGNUP_INFO");
        assertThat(login(EMAIL, code, "홍길동", "20230001")).hasStatusOk();
    }

    @Test
    @DisplayName("이미 쓰인 학번으로 가입하면 409")
    void studentIdTaken() {
        sendCode(EMAIL);
        login(EMAIL, mail.last.get(EMAIL), "홍길동", "20230001");

        String other = "kim@chosun.ac.kr";
        sendCode(other);
        assertThat(login(other, mail.last.get(other), "김철수", "20230001")).hasStatus(409).bodyJson()
                .extractingPath("$.code").isEqualTo("STUDENT_ID_TAKEN");
    }

    @Test
    @DisplayName("메일을 못 보내면 503, 기록이 남지 않아 바로 다시 받을 수 있다")
    void mailFailure() {
        mail.broken = true;
        assertThat(sendCode(EMAIL)).hasStatus(503).bodyJson()
                .extractingPath("$.code").isEqualTo("MAIL_SEND_FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM email_verification").query(Integer.class).single()).isZero();

        mail.broken = false;
        assertThat(sendCode(EMAIL)).hasStatus(204);
    }

    @Test
    @DisplayName("같은 메일로 거의 동시에 가입하면 늦은 쪽은 학번이 아니라 메일 중복으로 409")
    void sameEmailRace() {
        members.insertVerified(EMAIL, "20230001", "홍길동", START);
        assertThatThrownBy(() -> members.insertVerified(EMAIL, "20239999", "홍길동", START))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_SIGNED_UP));
    }

    @Test
    @DisplayName("번호가 맞으면 틀린 횟수로 세지 않는다: 4번 틀리고 가입 정보만 빠뜨려도 같은 번호로 가입된다")
    void correctCodeIsNotCounted() {
        sendCode(EMAIL);
        String code = mail.last.get(EMAIL);
        for (int i = 0; i < 4; i++) {
            login(EMAIL, wrong(code), null, null);
        }
        assertThat(login(EMAIL, code, null, null)).hasStatus(400).bodyJson()
                .extractingPath("$.code").isEqualTo("SIGNUP_INFO_REQUIRED");
        assertThat(login(EMAIL, code, "홍길동", "20230001")).hasStatusOk();
    }

    @Test
    @DisplayName("같은 주소로 동시에 여러 번 요청해도 번호는 하나만 만들어진다")
    void concurrentSendCreatesOneCode() throws Exception {
        int n = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return sendCode(EMAIL).getResponse().getStatus();
            }));
        }
        int ok = 0;
        try {
            start.countDown();
            for (var f : results) {
                int status = f.get();
                assertThat(status).isIn(204, 429);   // 나머지는 "1분 안에 또" 429여야 한다 (500이면 실패)
                if (status == 204) {
                    ok++;
                }
            }
        } finally {
            // 검증이 중간에 실패해도 남은 요청이 끝난 뒤에 clean()이 테이블을 비우게 기다린다
            pool.shutdown();
            if (!pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }

        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM email_verification").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("새 번호가 발급된 뒤에는 옛 번호를 사용 처리할 수 없다 (로그인 도중 재발급 경합)")
    void olderCodeCannotBeConsumedAfterNewerIssued() {
        Instant t = clock.instant();
        long older = verifications.save(EMAIL, "a", t, t.plus(Duration.ofMinutes(10)));
        long newer = verifications.save(EMAIL, "b", t.plusSeconds(61), t.plus(Duration.ofMinutes(11)));

        assertThat(verifications.markUsed(older, t.plusSeconds(62))).isFalse();
        assertThat(verifications.markUsed(newer, t.plusSeconds(62))).isTrue();
    }
    // ---------- 자동 로그인 (refresh 토큰) ----------

    String json(MvcTestResult r, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(
                r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), path).toString();
    }

    /** 가입하고 로그인 응답을 돌려준다 */
    MvcTestResult signUp() {
        sendCode(EMAIL);
        return login(EMAIL, mail.last.get(EMAIL), "홍길동", "20230001");
    }

    MvcTestResult refresh(String refreshToken) {
        return mvc.post().uri("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\": \"%s\"}".formatted(refreshToken)).exchange();
    }

    MvcTestResult logout(String refreshToken) {
        return mvc.post().uri("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\": \"%s\"}".formatted(refreshToken)).exchange();
    }

    @Test
    @DisplayName("로그인하면 액세스 토큰 1시간 + refresh 토큰 14일, DB에는 refresh 토큰 해시만")
    void loginGivesRefreshToken() throws Exception {
        MvcTestResult r = signUp();
        assertThat(r).hasStatusOk();
        assertThat(Instant.parse(json(r, "$.expiresAt"))).isEqualTo(START.plus(Duration.ofHours(1)));
        assertThat(Instant.parse(json(r, "$.refreshExpiresAt"))).isEqualTo(START.plus(Duration.ofDays(14)));
        String refreshToken = json(r, "$.refreshToken");
        assertThat(refreshToken).hasSizeGreaterThanOrEqualTo(43);

        assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE token_hash = :raw")
                .param("raw", refreshToken).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE member_id = 1 AND revoked_at IS NULL")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("자동 로그인: 액세스 토큰이 끝나도 refresh 토큰으로 새 한 쌍을 받고, 받은 refresh 토큰은 다시 못 쓴다")
    void refreshRotates() throws Exception {
        String first = json(signUp(), "$.refreshToken");
        clock.plus(Duration.ofHours(2));   // 액세스 토큰은 끝남

        MvcTestResult r = refresh(first);
        assertThat(r).hasStatusOk().bodyJson().satisfies(j -> {
            j.assertThat().extractingPath("$.memberId").isEqualTo(1);
            j.assertThat().extractingPath("$.newMember").isEqualTo(false);
            j.assertThat().extractingPath("$.tokenType").isEqualTo("Bearer");
        });
        assertThat(tokens.verify(json(r, "$.accessToken"))).hasValueSatisfying(v -> assertThat(v.memberId()).isEqualTo(1L));
        String second = json(r, "$.refreshToken");
        assertThat(second).isNotEqualTo(first);

        assertThat(refresh(second)).hasStatusOk();
    }

    @Test
    @DisplayName("쓸 때마다 14일이 새로 시작: 13일마다 쓰면 계속 이어지고, 14일 동안 안 쓰면 401")
    void slidingExpiry() throws Exception {
        String t = json(signUp(), "$.refreshToken");
        for (int i = 0; i < 3; i++) {
            clock.plus(Duration.ofDays(13));
            MvcTestResult r = refresh(t);
            assertThat(r).hasStatusOk();
            t = json(r, "$.refreshToken");
        }
        clock.plus(Duration.ofDays(14));
        assertThat(refresh(t)).hasStatus(401).bodyJson()
                .extractingPath("$.code").isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    @DisplayName("이미 쓴 refresh 토큰이 다시 오면 도난으로 보고 그 회원 토큰을 모두 끊는다")
    void reusedTokenRevokesAll() throws Exception {
        String stolen = json(signUp(), "$.refreshToken");
        String current = json(refresh(stolen), "$.refreshToken");   // 진짜 학생이 먼저 바꿈

        assertThat(refresh(stolen)).hasStatus(401);                  // 훔친 쪽이 옛 토큰을 씀
        assertThat(refresh(current)).hasStatus(401).bodyJson()       // 진짜 학생도 다시 로그인해야 함
                .extractingPath("$.code").isEqualTo("REFRESH_TOKEN_INVALID");
        assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL")
                .query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("로그아웃하면 그 refresh 토큰은 바로 못 쓴다. 모르는 토큰이어도 204")
    void logoutRevokes() throws Exception {
        String t = json(signUp(), "$.refreshToken");
        assertThat(logout(t)).hasStatus(204);
        assertThat(refresh(t)).hasStatus(401);
        assertThat(logout("not-a-token")).hasStatus(204);
    }

    @Test
    @DisplayName("없는 토큰·빈 값이면 401")
    void unknownRefreshToken() {
        assertThat(refresh("nope")).hasStatus(401);
        assertThat(refresh("")).hasStatus(401);
    }
}
