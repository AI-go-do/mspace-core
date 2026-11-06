package kr.ac.chosun.mspace.member.application;

/** 참여자로 넣을 회원을 학번으로 찾는 입력 포트 (FR-RSV-004) */
public interface MemberSearchUseCase {

    /**
     * 학생 인증을 마친 회원만 찾는다. 이름은 가려서 돌려준다 (홍*동).
     *
     * @param studentId 학번 (앞뒤 공백은 지운다)
     * @param requesterId 찾는 사람(로그인한 회원). 찾은 사람이 본인인지(me) 알려 주는 데만 쓴다
     * @return 찾은 회원
     * @throws kr.ac.chosun.mspace.common.error.BusinessException 비었거나 20자를 넘으면 400, 없거나 인증 전이면 404
     */
    FoundMember findByStudentId(String studentId, long requesterId);

    /**
     * 검색 결과. 실명·이메일·회원 id는 담지 않는다.
     *
     * @param me 찾은 사람이 나(대표자)면 true. 화면이 "추가" 대신 "추가됨"을 보여 준다 (대표자는 이미 들어 있음)
     */
    record FoundMember(String studentId, String name, boolean me) {
    }
}
