package kr.ac.chosun.mspace.reservation.domain;

/**
 * 폰에 띄우는 입실 QR 안의 글자 (11/6 지영: core는 글자만 주고 그림은 화면이 그린다).
 * 문 앞 웹캠(mspace-device qr_door.py)이 이 글자를 읽어 키패드와 같은 POST /api/access/verify로 묻는다.
 * 형식: {@code MSPACE:<방 번호>:<6자리 입실 번호>}. 방 번호가 있어서 다른 방 문에서는 서버에 묻기 전에 거부된다.
 */
public final class AccessQr {

    private AccessQr() {
    }

    /** 입실 번호가 없으면(참여자, 노쇼·취소 등으로 숨긴 번호) QR도 없다. */
    public static String text(int roomId, String accessCode) {
        return accessCode == null ? null : "MSPACE:" + roomId + ":" + accessCode;
    }
}
