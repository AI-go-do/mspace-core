package kr.ac.chosun.mspace.reservation.application;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 키패드 6자리 번호 (FR-ACC-001). Random이 아니라 SecureRandom인 이유: 다음 번호를 예측할 수 없어야 해서.
 * 같은 방 끝나지 않은 예약끼리 번호 중복 금지는 키패드 확인(#8)에서 Flyway로 DB 제약을 더한다.
 */
@Component
public class AccessCodeGenerator {

    private final SecureRandom random = new SecureRandom();

    public String next() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
