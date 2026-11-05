package kr.ac.chosun.mspace.member.application;

import java.util.Optional;

/** 회원 조회에 필요한 DB 일. 구현은 adapter/out/persistence */
public interface MemberQueryPort {

    /** 학생 인증을 마친(verified_at 있음) 회원의 id와 실명. 없거나 인증 전이면 빈 값 */
    Optional<VerifiedMember> findVerified(String studentId);

    record VerifiedMember(long id, String name) {
    }
}
