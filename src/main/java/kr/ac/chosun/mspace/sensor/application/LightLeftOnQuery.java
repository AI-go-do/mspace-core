package kr.ac.chosun.mspace.sensor.application;

import java.time.Instant;
import java.util.Map;

/** 지금 "불 켜짐"인 방 (11/12 지영, 관리자 "지금" 탭 표시용). 방 번호 → 처음 켜짐을 본 시각 */
public interface LightLeftOnQuery {

    Map<Integer, Instant> leftOn();
}
