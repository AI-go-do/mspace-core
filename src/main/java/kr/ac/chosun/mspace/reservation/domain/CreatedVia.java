package kr.ac.chosun.mspace.reservation.domain;

/**
 * 예약을 어디서 만들었나 (reservation.created_via, NFR-COM-008). WEB/AGENT 건수를 따로 세는 데 쓴다.
 * DEVICE는 DB CHECK에는 있지만 아직 장치가 예약을 만들지 않아 쓰지 않는다.
 */
public enum CreatedVia {
    WEB, AGENT;

    /**
     * X-Created-Via 헤더 값으로 고른다. 없으면 웹 화면(WEB), "AGENT"면 비서(AGENT). 대소문자·앞뒤 공백은 무시한다.
     * 그 밖의 값은 null: 오타("agnet")를 WEB으로 조용히 세면 비서 숫자가 틀어져서, 컨트롤러가 400으로 막는다.
     * 이 값은 통계용 표시일 뿐 권한과는 상관없다 (권한은 로그인 토큰으로 본다)
     */
    public static CreatedVia fromHeader(String header) {
        if (header == null) {
            return WEB;
        }
        return switch (header.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "web" -> WEB;
            case "agent" -> AGENT;
            default -> null;
        };
    }
}
