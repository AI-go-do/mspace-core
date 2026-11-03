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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 학교 메일 인증 로그인 (FR-AUTH-001).
 * <p>
 * 일부러 메서드 전체를 트랜잭션으로 묶지 않는다. 시도 횟수(+1)는
 * 예외를 던져도 저장돼야 하기 때문이다. 성공했을 때만 "번호 사용 처리 + 회원 저장"을 한 트랜잭션으로 묶는다.
 */
@Service
class EmailLoginService implements EmailLoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(EmailLoginService.class);
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
        String code = newCode();
        // "1분 안에 보냈나 확인 → 저장"을 같은 주소끼리 한 줄로 세운다 (동시에 두 번 눌러도 번호는 하나)
        long id = tx.execute(status -> {
            verifications.lockEmail(email);
            verifications.findLatest(email)
                    .filter(last -> now.isBefore(last.createdAt().plus(EmailCodeRule.RESEND_INTERVAL)))
                    .ifPresent(last -> {
                        throw new BusinessException(ErrorCode.EMAIL_CODE_TOO_SOON);
                    });
            return verifications.save(email, hash(email, code), now, now.plus(EmailCodeRule.VALID_FOR));
        });
        // 메일은 트랜잭션 밖에서 보낸다 (SMTP가 느려도 DB 잠금을 오래 잡지 않게)
        try {
            sender.send(email, code);
        } catch (RuntimeException e) {
            // 못 보낸 번호가 남아 있으면 1분 동안 다시 받을 수 없으니 지운다
            verifications.delete(id);
            log.warn("인증번호 메일 발송 실패: {}", e.toString());
            throw new BusinessException(ErrorCode.MAIL_SEND_FAILED);
        }
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
        if (!now.isBefore(v.expiresAt())) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_EXPIRED);
        }
        // 비교 전에 시도 1번을 먼저 쓴다. 동시에 여러 번 보내도 5번까지만 비교된다
        if (!verifications.tryAttempt(v.id(), EmailCodeRule.MAX_FAILED_ATTEMPTS)) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_TOO_MANY_ATTEMPTS);
        }
        String code = command.code() == null ? "" : command.code().strip();
        if (!MessageDigest.isEqual(hash(email, code).getBytes(StandardCharsets.US_ASCII),
                v.codeHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_MISMATCH);
        }
        // 맞았으면 틀린 횟수로 세지 않는다
        verifications.refundAttempt(v.id());

        // 번호는 맞음. 처음이면 가입 정보가 있어야 한다 (이때 번호는 아직 쓰지 않은 상태로 남겨 다시 보낼 수 있게)
        Optional<Long> existing = members.findIdByEmail(email);
        Signup signup = existing.isPresent() ? null : Signup.from(command);

        long memberId = tx.execute(status -> {
            // 번호 발급(sendCode)과 같은 잠금을 잡는다. 새 번호가 커밋됐으면 markUsed가 옛 번호를 거절한다
            verifications.lockEmail(email);
            if (!verifications.markUsed(v.id(), now)) {
                // 같은 번호로 동시에 두 번 들어왔거나, 그사이 새 번호가 발급된 경우
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
