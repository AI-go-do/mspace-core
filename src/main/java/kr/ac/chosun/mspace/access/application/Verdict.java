package kr.ac.chosun.mspace.access.application;

/**
 * 키패드 응답: {"result":"OPENED"} 또는 {"result":"DENIED","reason":"WRONG_CODE"}.
 * 이름은 수연 키패드 중계(mspace-device README 7절)와 맞춤 (11/2 합의).
 *
 * @param reason OPENED면 null. WRONG_CODE(틀림), NOT_YET(아직 이름), LOCKED(5번 틀려 잠김)
 */
public record Verdict(String result, String reason) {

    public static final Verdict OPENED = new Verdict("OPENED", null);

    public static Verdict denied(String reason) {
        return new Verdict("DENIED", reason);
    }

    public boolean opened() {
        return reason == null;
    }
}
