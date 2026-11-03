package kr.ac.chosun.mspace.member.application;

import java.time.Instant;
import java.util.Optional;

/** 인증번호 기록을 저장·조회하는 창구(나가는 포트). 구현은 adapter/out/persistence */
public interface EmailVerificationPort {

    /**
     * 새 번호 기록을 남긴다 (번호는 해시로).
     *
     * @return 새 기록 id
     */
    long save(String email, String codeHash, Instant createdAt, Instant expiresAt);

    /** 메일을 못 보냈을 때 방금 남긴 기록을 지운다 (안 지우면 1분 동안 다시 못 받는다) */
    void delete(long id);

    /** 이 주소로 가장 최근에 보낸 번호 기록 */
    Optional<Verification> findLatest(String email);

    /**
     * 번호를 비교하기 전에 시도 1번을 먼저 쓴다 (틀린 횟수 +1).
     * 확인과 증가를 UPDATE 한 줄로 해서, 동시에 여러 번 보내도 5번을 넘겨 비교할 수 없다.
     * 트랜잭션 밖에서 불려도 바로 저장돼야 한다.
     *
     * @return 시도할 수 있었으면 true, 이미 5번을 다 썼거나 쓴 번호면 false
     */
    boolean tryAttempt(long id, int maxAttempts);

    /** 번호가 맞았으면 미리 쓴 시도 1번을 돌려준다 (가입 정보만 틀린 경우 같은 번호로 다시 할 수 있게) */
    void refundAttempt(long id);

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
