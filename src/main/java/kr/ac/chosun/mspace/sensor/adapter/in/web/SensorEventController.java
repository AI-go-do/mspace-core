package kr.ac.chosun.mspace.sensor.adapter.in.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import kr.ac.chosun.mspace.common.auth.DeviceTokenVerifier;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.sensor.application.RecordSensorEventUseCase;
import kr.ac.chosun.mspace.sensor.application.RecordSensorEventUseCase.Kind;
import kr.ac.chosun.mspace.sensor.application.RecordSensorEventUseCase.SensorEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 재실·CO2 센서 장치가 부르는 입구 (FR-DEV-101). 키패드와 같은 장치 토큰(Authorization: Bearer)으로 막는다.
 * 형식: mspace-device docs/sensor-events-api.md (수연 초안, 11/5 지영 확정).
 * 장치 토큰을 장치·방마다 나누는 것(403 DEVICE_ROOM_MISMATCH)은 재웅 답 대기라 아직 없다.
 */
@RestController
class SensorEventController {

    private static final int MAX_SOURCES_LENGTH = 1000;

    private final RecordSensorEventUseCase service;
    private final DeviceTokenVerifier devices;

    SensorEventController(RecordSensorEventUseCase service, DeviceTokenVerifier devices) {
        this.service = service;
        this.devices = devices;
    }

    /**
     * 예: POST /api/sensor-events, Authorization: Bearer 장치토큰
     * {"eventId":"…","roomId":1,"deviceId":"sensor-1","kind":"OCCUPANCY","occupied":false,
     *  "sources":{"pir":false,"radar":false},"observedAt":"2026-11-04T14:05:00+09:00"}
     * {"eventId":"…","roomId":1,"deviceId":"sensor-1","kind":"CO2","ppm":1080,"source":"scd40",
     *  "temperatureC":25.4,"humidityPct":47,"observedAt":"…"}   (온도·습도는 선택, CO2에만)
     * → 200 {"result":"STORED"} 또는 {"result":"DUPLICATE"}. 401 토큰 문제, 400 형식 문제(장치는 다시 안 보냄).
     */
    @PostMapping("/api/sensor-events")
    SensorEventResponse record(@RequestHeader(name = "Authorization", required = false) String authorization,
                               @RequestBody SensorEventRequest request) {
        devices.verify(authorization, request.roomId(), request.deviceId());
        return new SensorEventResponse(service.record(request.toEvent()).name());
    }

    record SensorEventRequest(String eventId, Integer roomId, String deviceId, String kind, Boolean occupied,
                              JsonNode sources, Integer ppm, String source, Double temperatureC, Double humidityPct,
                              String observedAt) {

        SensorEvent toEvent() {
            if (eventId == null || eventId.isBlank() || eventId.length() > 64 || roomId == null
                    || deviceId == null || deviceId.isBlank() || deviceId.length() > 50
                    || (source != null && source.length() > 30) || observedAt == null
                    || (sources != null && !sources.isNull() && !sources.isObject())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            String sourcesJson = sources == null || sources.isNull() ? null : sources.toString();
            if (sourcesJson != null && sourcesJson.length() > MAX_SOURCES_LENGTH) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            return new SensorEvent(eventId, roomId, deviceId, parsedKind(), occupied, sourcesJson, ppm, source,
                    oneDecimal(temperatureC), oneDecimal(humidityPct), observed(), light(), pir());
        }

        /** sources.light: 우노 조도 원시값 0–1023 (mspace-device gateway). 숫자가 아니거나 범위 밖이면 없는 것으로 */
        private Integer light() {
            JsonNode v = sources == null ? null : sources.get("light");
            return v != null && v.isNumber() && v.asDouble() >= 0 && v.asDouble() <= 1023
                    ? (int) Math.round(v.asDouble()) : null;
        }

        /** sources.pir: PIR 움직임 감지. 참/거짓이 아니면 없는 것으로 */
        private Boolean pir() {
            JsonNode v = sources == null ? null : sources.get("pir");
            return v != null && v.isBoolean() ? v.booleanValue() : null;
        }

        /** DB 칸이 소수 한 자리라 반올림해 둔다 (25.46 → 25.5) */
        private static Double oneDecimal(Double v) {
            return v == null ? null : Math.round(v * 10) / 10.0;
        }

        private Kind parsedKind() {
            try {
                return Kind.valueOf(String.valueOf(kind));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }

        /** 오프셋(+09:00) 없는 시각은 400: 장치 시계가 어느 시간대인지 모르면 10·15분을 잴 수 없다 */
        private Instant observed() {
            try {
                return OffsetDateTime.parse(observedAt).toInstant();
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
    }

    record SensorEventResponse(String result) {
    }
}
