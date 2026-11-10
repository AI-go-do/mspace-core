package kr.ac.chosun.mspace.access.application;

/**
 * 키패드 응답: {"result":"OPENED"} 또는 {"result":"DENIED","reason":"WRONG_CODE"}.
 * 이름은 수연 키패드 중계(mspace-device README 7절)와 맞춤 (11/2 합의).
 * 두고 간 물건 찾으러 온 QR로 열리면 {"result":"OPENED","purpose":"RETRIEVE"} (11/10 수연 요청).
 * 장치는 RETRIEVE면 새 팀 입실로 보지 않는다
 *
 * @param reason  OPENED면 null. WRONG_CODE(틀림), NOT_YET(아직 이름), LOCKED(5번 틀려 잠김)
 * @param purpose 찾으러 온 QR이면 RETRIEVE. 그 밖에는 null
 */
public record Verdict(String result, String reason, String purpose) {

    public static final String RETRIEVE = "RETRIEVE";
    public static final Verdict OPENED = new Verdict("OPENED", null, null);
    public static final Verdict RETRIEVE_OPENED = new Verdict("OPENED", null, RETRIEVE);

    public static Verdict denied(String reason) {
        return new Verdict("DENIED", reason, null);
    }

    public boolean opened() {
        return reason == null;
    }
}
