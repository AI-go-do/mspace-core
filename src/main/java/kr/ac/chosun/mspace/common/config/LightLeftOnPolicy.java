package kr.ac.chosun.mspace.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 빈 방 불 켜짐 (11/12 지영). 예약도 사람도 없는 방의 조도가 기준 이상으로 이어지면 마지막 팀에게 알리고
 * 관리자 "지금" 탭에 "불 켜짐"을 띄운다. application.yml에서 읽는다.
 *
 * @param threshold  조도 원시값(0–1023, 밝을수록 큼) 이 값 이상이면 켜짐으로 본다. 햇빛과 전등을 구분하지 못해서
 *                   수연이 낮에 불 끈 값·켠 값을 재고 맞춘다 (처음 600)
 * @param after      켜짐이 이만큼 이어져야 "불 켜짐"으로 본다 (퇴실하며 끄는 사이에 알리지 않게, 10분)
 * @param teamWithin 마지막 팀은 처음 켜짐을 본 시각보다 이만큼 안에 퇴실했을 때만 알린다. 그보다 오래됐으면
 *                   그 팀이 켜 둔 게 아닐 수 있어 관리자 표시만 (30분)
 */
@ConfigurationProperties("mspace.sensor.light-left-on")
public record LightLeftOnPolicy(int threshold, Duration after, Duration teamWithin) {

    public LightLeftOnPolicy {
        if (threshold < 0 || threshold > 1023) {
            throw new IllegalArgumentException("mspace.sensor.light-left-on.threshold는 0–1023이어야 합니다 (%d)"
                    .formatted(threshold));
        }
        if (after == null || after.isNegative() || teamWithin == null || teamWithin.isNegative()) {
            throw new IllegalArgumentException("mspace.sensor.light-left-on.after·team-within은 0 이상이어야 합니다");
        }
    }
}
