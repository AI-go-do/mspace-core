package kr.ac.chosun.mspace.common.privacy;

/**
 * 다른 사람에게 보여 줄 이름 가리기 (V1 주석 3번: "이름 일부만 보여 주기, 예: 김*영").
 * 첫 글자와 끝 글자만 남기고 가운데를 *로 바꾼다. 두 글자면 끝 글자를 가린다.
 */
public final class MaskedName {

    private MaskedName() {
    }

    /**
     * @param name 실명 (null이나 빈 값이면 빈 문자열)
     * @return 김*영, 남궁**수, 이*
     */
    public static String of(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String n = name.strip();
        int len = n.codePointCount(0, n.length());
        if (len == 1) {
            return n;
        }
        String first = new String(Character.toChars(n.codePointAt(0)));
        if (len == 2) {
            return first + "*";
        }
        String last = new String(Character.toChars(n.codePointBefore(n.length())));
        return first + "*".repeat(len - 2) + last;
    }
}
