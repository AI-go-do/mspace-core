package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 두고 간 물건 다시 확인 (FR-DEV-105). 10차 회의(11/10)의 "2시간(→3시간) 안에 안 가져가면 경고"는
 * 11차 회의(11/13)에서 없앴다: 알림만 보내고 경고하지 않는다(docs 요구사항 v0.4.40). application.yml에서 읽는다.
 *
 * @param recheckAfterMinutes  퇴실 뒤 이 시간이 지나면 방 카메라가 한 번 더 본다(분)
 * @param warnMinConfidence    다시 볼 때 확신도가 이 값 이상인 물건이 하나라도 남아 있으면 경고 (0~1).
 *                             11/10 지영: 0.8로 시작하고 모델 비교 결과를 보고 조정
 * @param retrievePassMinutes "찾으러 갈게요"를 누르면 주는 입실 QR이 살아 있는 시간(분). 11/10 지영: 10분
 * @param cameraWarn          방 카메라 결과만으로 경고·사실 판정을 할지. 11/11 지영: 카메라 정확도(51%)가 오를 때까지 끔,
 *                            꺼져 있으면 알림만 보낸다. 11/13 11차 회의: 카메라로는 경고하지 않기로 해서 켜지 않는다
 */
@ConfigurationProperties("mspace.tidy")
public record TidyPolicy(int recheckAfterMinutes, double warnMinConfidence, int retrievePassMinutes, boolean cameraWarn) {

    public TidyPolicy {
        if (warnMinConfidence <= 0 || warnMinConfidence > 1) {
            throw new IllegalArgumentException("mspace.tidy.warn-min-confidence는 0보다 크고 1 이하여야 합니다 (%s)"
                    .formatted(warnMinConfidence));
        }
        if (recheckAfterMinutes < 1) {
            throw new IllegalArgumentException("mspace.tidy.recheck-after-minutes는 1 이상이어야 합니다 (%d)"
                    .formatted(recheckAfterMinutes));
        }
        if (retrievePassMinutes < 1) {
            throw new IllegalArgumentException("mspace.tidy.retrieve-pass-minutes는 1 이상이어야 합니다 (%d)"
                    .formatted(retrievePassMinutes));
        }
    }

    /** 사람이 읽는 기한: 180 → "3시간", 150 → "2시간 30분", 90 → "1시간 30분", 45 → "45분". 알림·이용 수칙 문구가 같이 쓴다 */
    public String recheckAfterText() {
        int hours = recheckAfterMinutes / 60;
        int minutes = recheckAfterMinutes % 60;
        if (hours == 0) {
            return minutes + "분";
        }
        return minutes == 0 ? hours + "시간" : hours + "시간 " + minutes + "분";
    }
}
