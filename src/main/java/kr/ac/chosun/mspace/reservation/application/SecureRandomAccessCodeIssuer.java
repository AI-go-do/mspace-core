package kr.ac.chosun.mspace.reservation.application;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** Random이 아니라 SecureRandom인 이유: 다음 번호를 예측할 수 없어야 해서 */
@Component
class SecureRandomAccessCodeIssuer implements AccessCodeIssuer {

    private final SecureRandom random = new SecureRandom();

    @Override
    public String issue() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
