package kr.ac.chosun.mspace.common.auth;

/**
 * 지금 요청을 보낸 회원. 컨트롤러 파라미터에 적으면 LoginUserArgumentResolver가 채운다.
 * resolver가 액세스 토큰(JWT)을 읽는다. 이 타입을 쓰는 코드는 로그인 방식이 바뀌어도 그대로 둔다.
 */
public record LoginUser(long id) {
}
