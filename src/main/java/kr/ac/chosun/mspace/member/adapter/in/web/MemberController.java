package kr.ac.chosun.mspace.member.adapter.in.web;

import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.member.application.MemberSearchUseCase;
import kr.ac.chosun.mspace.member.application.MemberSearchUseCase.FoundMember;
import kr.ac.chosun.mspace.member.application.MemberSearchUseCase.MyProfile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 회원 API 입구 */
@RestController
class MemberController {

    private final MemberSearchUseCase search;

    MemberController(MemberSearchUseCase search) {
        this.search = search;
    }

    /**
     * 참여자로 넣을 회원을 학번으로 찾는다 (FR-RSV-004). 로그인한 회원만 쓸 수 있다.
     * 예: GET /api/members?studentId=20230001 → {"studentId":"20230001","name":"홍*동","me":false}
     *
     * @param user      로그인한 회원 (없으면 401). 찾은 사람이 본인인지(me)만 판단한다
     * @param studentId 찾을 학번
     * @return 학번과 가린 이름. 없거나 학생 인증 전이면 404(MEMBER_NOT_FOUND)
     */
    @GetMapping("/api/members")
    ResponseEntity<FoundMember> find(LoginUser user, @RequestParam(required = false) String studentId) {
        // me가 로그인한 사람마다 달라서, 계정을 바꾼 뒤 브라우저가 옛 응답을 다시 쓰지 않게 저장 금지
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(search.findByStudentId(studentId, user.id()));
    }

    /**
     * 내 정보 (마이 화면). 예: GET /api/members/me → {"studentId":"20230001","name":"홍길동"}
     * 이메일·회원 id는 화면에 안 써서 내보내지 않는다. 사람마다 달라서 브라우저 저장 금지
     *
     * @return 200 / 401 로그인 안 함 / 404 회원 없음(MEMBER_NOT_FOUND)
     */
    @GetMapping("/api/members/me")
    ResponseEntity<MyProfile> me(LoginUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.myProfile(user.id()));
    }
}
