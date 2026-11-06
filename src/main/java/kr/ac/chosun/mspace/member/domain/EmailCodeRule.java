package kr.ac.chosun.mspace.member.domain;

import java.time.Duration;

/**
 * 인증번호 규칙의 숫자 (요구사항 FR-AUTH-001). 바꿀 일이 생기면 여기 한 곳만 고친다.
 */
public final class EmailCodeRule {

    /** 번호 자릿수 */
    public static final int DIGITS = 6;
    /** 보낸 뒤 이 시간이 지나면 못 쓴다 */
    public static final Duration VALID_FOR = Duration.ofMinutes(10);
    /** 이만큼 틀리면 그 번호는 끝. 다시 받아야 한다 */
    public static final int MAX_FAILED_ATTEMPTS = 5;
    /** 같은 주소로 이 시간 안에 다시 보내지 않는다 */
    public static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);

    private EmailCodeRule() {
    }
}
