package kr.ac.chosun.mspace.common.auth;

/**
 * 지금 요청을 보낸 회원. 컨트롤러 파라미터에 적으면 LoginUserArgumentResolver가 채운다.
 * 로그인(재웅님, FR-AUTH)이 붙으면 resolver만 JWT를 읽게 바꾸고, 이 타입을 쓰는 코드는 그대로 둔다.
 */
public record LoginUser(long id) {
}
