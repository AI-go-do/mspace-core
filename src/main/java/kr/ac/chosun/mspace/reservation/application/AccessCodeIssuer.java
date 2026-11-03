package kr.ac.chosun.mspace.reservation.application;

/**
 * 키패드 6자리 번호를 내주는 창구 (FR-ACC-001). 인터페이스로 둔 이유: 테스트에서 고정 번호를 끼우고,
 * 키패드 확인(#8)에서 "같은 방 끝나지 않은 예약끼리 번호 중복 금지" 규칙을 붙일 자리를 만들기 위해.
 */
public interface AccessCodeIssuer {

    /** 000000~999999 중 하나, 앞자리 0을 살린 6자리 문자열 */
    String issue();
}
