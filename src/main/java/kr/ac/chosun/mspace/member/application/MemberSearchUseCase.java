package kr.ac.chosun.mspace.member.application;

/** 참여자로 넣을 회원을 학번으로 찾는 입력 포트 (FR-RSV-004) */
public interface MemberSearchUseCase {

    /**
     * 학생 인증을 마친 회원만 찾는다. 이름은 가려서 돌려준다 (김*영).
     *
     * @param studentId 학번 (앞뒤 공백은 지운다)
     * @return 찾은 회원
     * @throws kr.ac.chosun.mspace.common.error.BusinessException 비었거나 20자를 넘으면 400, 없거나 인증 전이면 404
     */
    FoundMember findByStudentId(String studentId);

    /** 검색 결과. 실명·이메일·회원 id는 담지 않는다 */
    record FoundMember(String studentId, String name) {
    }
}
