package kr.ac.chosun.mspace.member.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import kr.ac.chosun.mspace.common.auth.JwtTokens;
import kr.ac.chosun.mspace.common.auth.JwtTokens.Issued;
import kr.ac.chosun.mspace.common.auth.JwtTokens.Scopes;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.member.application.EmailVerificationPort.Verification;
import kr.ac.chosun.mspace.member.domain.EmailCodeRule;
import kr.ac.chosun.mspace.member.domain.SchoolEmail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 학교 메일 인증 로그인 (FR-AUTH-001).
 * <p>
 * 일부러 메서드 전체를 트랜잭션으로 묶지 않는다. 번호가 틀렸을 때 "틀린 횟수 +1"은
 * 예외를 던져도 저장돼야 하기 때문이다. 성공했을 때만 "번호 사용 처리 + 회원 저장"을 한 트랜잭션으로 묶는다.
 */
@Service
class EmailLoginService implements EmailLoginUseCase {

    private static final Pattern STUDENT_ID = Pattern.compile("^\\d{1,20}$");
    private static final int MAX_NAME_LENGTH = 50;

    private final EmailVerificationPort verifications;
    private final MemberPort members;
    private final VerificationCodeSender sender;
    private final JwtTokens tokens;
    private final TransactionOperations tx;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    EmailLoginService(EmailVerificationPort verifications, MemberPort members, VerificationCodeSender sender,
                      JwtTokens tokens, TransactionOperations tx, Clock clock) {
        this.verifications = verifications;
        this.members = members;
        this.sender = sender;
        this.tokens = tokens;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void sendCode(String rawEmail) {
        String email = SchoolEmail.of(rawEmail).value();
        Instant now = clock.instant();
        verifications.findLatest(email)
                .filter(last -> now.isBefore(last.createdAt().plus(EmailCodeRule.RESEND_INTERVAL)))
                .ifPresent(last -> {
                    throw new BusinessException(ErrorCode.EMAIL_CODE_TOO_SOON);
                });

        String code = newCode();
        verifications.save(email, hash(email, code), now, now.plus(EmailCodeRule.VALID_FOR));
        sender.send(email, code);
    }

    @Override
    public LoginResult login(LoginCommand command) {
        String email = SchoolEmail.of(command.email()).value();
        Instant now = clock.instant();

        Verification v = verifications.findLatest(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_CODE_NOT_FOUND));
        if (v.used()) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_NOT_FOUND);
        }
        if (v.failedAttempts() >= EmailCodeRule.MAX_FAILED_ATTEMPTS) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_TOO_MANY_ATTEMPTS);
        }
        if (!now.isBefore(v.expiresAt())) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_EXPIRED);
        }
        String code = command.code() == null ? "" : command.code().strip();
        if (!MessageDigest.isEqual(hash(email, code).getBytes(StandardCharsets.US_ASCII),
                v.codeHash().getBytes(StandardCharsets.US_ASCII))) {
            verifications.addFailedAttempt(v.id());
            throw new BusinessException(ErrorCode.EMAIL_CODE_MISMATCH);
        }

        // 번호는 맞음. 처음이면 가입 정보가 있어야 한다 (이때 번호는 아직 쓰지 않은 상태로 남겨 다시 보낼 수 있게)
        Optional<Long> existing = members.findIdByEmail(email);
        Signup signup = existing.isPresent() ? null : Signup.from(command);

        long memberId = tx.execute(status -> {
            if (!verifications.markUsed(v.id(), now)) {
                // 같은 번호로 동시에 두 번 들어온 경우 늦은 쪽
                throw new BusinessException(ErrorCode.EMAIL_CODE_NOT_FOUND);
            }
            if (existing.isPresent()) {
                members.markVerifiedIfNot(existing.get(), now);
                return existing.get();
            }
            return members.insertVerified(email, signup.studentId(), signup.name(), now);
        });

        Issued issued = tokens.issue(memberId, List.of(Scopes.USER));
        return new LoginResult(issued.token(), issued.expiresAt(), memberId, existing.isEmpty());
    }

    private String newCode() {
        int bound = (int) Math.pow(10, EmailCodeRule.DIGITS);
        return String.format("%0" + EmailCodeRule.DIGITS + "d", random.nextInt(bound));
    }

    /** 번호는 주소와 묶어서 해시한다 (같은 번호라도 주소가 다르면 해시가 다름) */
    static String hash(String email, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((email + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Signup(String name, String studentId) {

        static Signup from(LoginCommand c) {
            String name = c.name() == null ? "" : c.name().strip();
            String studentId = c.studentId() == null ? "" : c.studentId().strip();
            if (name.isEmpty() || studentId.isEmpty()) {
                throw new BusinessException(ErrorCode.SIGNUP_INFO_REQUIRED);
            }
            if (name.length() > MAX_NAME_LENGTH || !STUDENT_ID.matcher(studentId).matches()) {
                throw new BusinessException(ErrorCode.INVALID_SIGNUP_INFO);
            }
            return new Signup(name, studentId);
        }
    }
}
