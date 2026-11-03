package kr.ac.chosun.mspace.member.application;

import java.time.Instant;
import java.util.Optional;

/** 회원(users) 저장·조회 창구(나가는 포트). 구현은 adapter/out/persistence */
public interface MemberPort {

    Optional<Long> findIdByEmail(String email);

    /**
     * 학생 인증을 마친 회원을 만든다.
     *
     * @return 새 회원 id
     * @throws kr.ac.chosun.mspace.common.error.BusinessException 학번(또는 메일)이 이미 있으면 409
     */
    long insertVerified(String email, String studentId, String name, Instant verifiedAt);

    /** 인증 시각이 비어 있으면 채운다 (예전에 만든 미인증 회원이 인증을 마친 경우) */
    void markVerifiedIfNot(long memberId, Instant verifiedAt);
}
