package kr.ac.chosun.mspace.member.domain;

import java.util.Locale;
import java.util.regex.Pattern;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;

/**
 * 학교 메일 주소 (값 객체). {@link #of}로만 만들고, 만들 때 "@chosun.ac.kr인지"를 확인한다.
 * 앞뒤 공백을 지우고 소문자로 맞춰서, A@Chosun.ac.kr과 a@chosun.ac.kr을 같은 사람으로 본다.
 * 규칙은 DB CHECK(users.email, email_verification.email)와 같다.
 */
public record SchoolEmail(String value) {

    private static final Pattern SCHOOL = Pattern.compile("^[^@\\s]+@chosun\\.ac\\.kr$");
    private static final int MAX_LENGTH = 100;

    /**
     * @throws BusinessException 학교 메일이 아니면 400 SCHOOL_EMAIL_ONLY
     */
    public static SchoolEmail of(String raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.SCHOOL_EMAIL_ONLY);
        }
        String email = raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() > MAX_LENGTH || !SCHOOL.matcher(email).matches()) {
            throw new BusinessException(ErrorCode.SCHOOL_EMAIL_ONLY);
        }
        return new SchoolEmail(email);
    }
}
