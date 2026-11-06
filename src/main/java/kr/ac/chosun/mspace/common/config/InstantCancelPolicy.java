package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 즉시 시작 예약의 취소 가능 시간 (FR-RSV-007, 11/6 팀 결정 "3–5분"). 코드에 박지 않고 application.yml에서 읽는다.
 * <p>
 * 즉시 시작은 누른 시각이 시작이라 "시작 전 취소"가 한 번도 안 돼서, 잘못 누르면 노쇼밖에 없었다.
 * 누른 뒤 이 시간 안이고 아직 입실 전(BOOKED)이면 대표자가 취소할 수 있다. 길게 열면 방을 잡아 두고 안 쓰는 데 쓰여서 짧게 둔다.
 *
 * @param minutes 누른 뒤 취소할 수 있는 분. 0이면 끔. 입실 마감(10분)보다 길 수 없다(DB 트리거가 입실 마감까지만 허락)
 */
@ConfigurationProperties("mspace.reservation.instant-cancel")
public record InstantCancelPolicy(int minutes) {

    public InstantCancelPolicy {
        if (minutes < 0 || minutes > 10) {
            throw new IllegalArgumentException(
                    "mspace.reservation.instant-cancel.minutes는 0–10이어야 합니다 (minutes=%d)".formatted(minutes));
        }
    }
}
