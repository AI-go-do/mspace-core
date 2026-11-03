package kr.ac.chosun.mspace.member.application;

import java.time.Instant;
import java.util.Optional;

/** 인증번호 기록을 저장·조회하는 창구(나가는 포트). 구현은 adapter/out/persistence */
public interface EmailVerificationPort {

    /** 새 번호 기록을 남긴다 (번호는 해시로) */
    void save(String email, String codeHash, Instant createdAt, Instant expiresAt);

    /** 이 주소로 가장 최근에 보낸 번호 기록 */
    Optional<Verification> findLatest(String email);

    /** 틀린 횟수를 1 올린다. 트랜잭션 밖에서 불려도 바로 저장돼야 한다 (틀린 기록이 롤백되면 안 됨) */
    void addFailedAttempt(long id);

    /**
     * 아직 안 쓴 번호를 "썼음"으로 바꾼다.
     *
     * @return 바꿨으면 true. 동시에 같은 번호로 두 번 들어오면 한쪽만 true
     */
    boolean markUsed(long id, Instant usedAt);

    record Verification(long id, String codeHash, Instant createdAt, Instant expiresAt,
                        int failedAttempts, boolean used) {
    }
}
