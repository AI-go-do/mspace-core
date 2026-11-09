package kr.ac.chosun.mspace.member.application;

import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.common.privacy.MaskedName;
import org.springframework.stereotype.Service;

@Service
class MemberSearchService implements MemberSearchUseCase {

    /** users.student_id 칸 길이 (V1 varchar(20)) */
    private static final int STUDENT_ID_MAX = 20;

    private final MemberQueryPort members;

    MemberSearchService(MemberQueryPort members) {
        this.members = members;
    }

    @Override
    public FoundMember findByStudentId(String studentId, long requesterId) {
        String id = studentId == null ? "" : studentId.strip();
        if (id.isEmpty() || id.length() > STUDENT_ID_MAX) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "학번을 1–20자로 넣어 주세요");
        }
        // 실명은 여기서 바로 가린다. 응답·로그 어디에도 실명이 나가지 않게
        return members.findVerified(id)
                .map(m -> new FoundMember(id, MaskedName.of(m.name()), m.id() == requesterId))
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    @Override
    public MyProfile myProfile(long memberId) {
        return members.findProfile(memberId)
                .map(p -> new MyProfile(p.studentId(), p.name()))
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
