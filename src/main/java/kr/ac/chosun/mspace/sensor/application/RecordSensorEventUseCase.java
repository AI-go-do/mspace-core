package kr.ac.chosun.mspace.sensor.application;

import java.time.Instant;

/**
 * 재실·CO2 센서 장치가 보내는 이벤트 받기 (FR-DEV-101). 형식은 mspace-device docs/sensor-events-api.md.
 * 장치는 "지금 어떤지"만 알리고, 자리 비움·환기 판단은 core가 한다.
 */
public interface RecordSensorEventUseCase {

    Result record(SensorEvent event);

    /**
     * 재실이면 occupied(필수)·sources(선택, JSON 글자), CO2면 ppm(필수)·source(선택)·temperatureC·humidityPct(선택,
     * SCD40이 같이 재는 값, 11/9 Q-21). light·pir은 sources에서 꺼낸 조도 원시값(0–1023)·PIR, 형식이 맞을 때만(보여 주기용)
     */
    record SensorEvent(String eventId, int roomId, String deviceId, Kind kind, Boolean occupied, String sources,
                       Integer ppm, String source, Double temperatureC, Double humidityPct, Instant observedAt,
                       Integer light, Boolean pir) {
    }

    enum Kind { OCCUPANCY, CO2 }

    /** STORED = 처음 받음(처리함), DUPLICATE = 같은 eventId를 이미 받음. 장치는 둘 다 성공으로 본다 */
    enum Result { STORED, DUPLICATE }
}
