package kr.ac.chosun.mspace.sensor.application;

import java.time.Instant;
import java.util.Map;

/**
 * 방마다 재실 이벤트 sources에 실려 온 조도·PIR 최신 값 (11/9 지영, 관리자 "지금" 탭에 보여 주기용).
 * 재실 판단에는 쓰지 않는다. sensor_event는 재실이 바뀔 때만 남기므로 여기서 메모리에 최신 값만 들고 있다.
 * 서버를 다시 켜면 비고, 다음 신호(10초마다)부터 다시 찬다.
 */
public interface RoomSignalsQuery {

    /** 방 번호 → 최신 값. 서버가 켜진 뒤 한 번도 안 온 방은 없다 */
    Map<Integer, Signals> latest();

    /**
     * @param light 조도 센서 원시값 0–1023 (우노 analogRead A0, CDS + 10kΩ 분압, 밝을수록 큼. lux 아님)
     * @param pir   PIR 움직임 감지
     */
    record Signals(Integer light, Instant lightAt, Boolean pir, Instant pirAt) {
    }
}
