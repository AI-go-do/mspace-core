package kr.ac.chosun.mspace.device.adapter.in.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.DeviceTokenVerifier;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.device.application.DeviceFaultUseCase;
import kr.ac.chosun.mspace.device.application.DeviceFaultUseCase.Attempt;
import kr.ac.chosun.mspace.device.application.DeviceFaultUseCase.Fault;
import kr.ac.chosun.mspace.device.application.DeviceFaultUseCase.Kind;
import kr.ac.chosun.mspace.device.application.DeviceFaultUseCase.Recorded;
import kr.ac.chosun.mspace.device.application.DeviceFaultUseCase.Via;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 문 장치 오류 기록 (Q-19). 키패드·센서와 같은 장치 토큰(Authorization: Bearer) */
@RestController
class DeviceFaultController {

    private static final int MAX_ATTEMPTS = 100;

    private final DeviceFaultUseCase faults;
    private final DeviceTokenVerifier devices;

    DeviceFaultController(DeviceFaultUseCase faults, DeviceTokenVerifier devices) {
        this.faults = faults;
        this.devices = devices;
    }

    /**
     * 예: POST /api/device-faults {"eventId":"qr-1-0005","roomId":1,"deviceId":"qr-1","kind":"SERVER_UNREACHABLE",
     * "startedAt":"…+09:00","endedAt":"…+09:00","attempts":[{"at":"…+09:00","code":"123456","via":"QR"}]}
     * → 200 {"result":"STORED","matched":1,"excused":1} 또는 {"result":"DUPLICATE",…}
     */
    @PostMapping("/api/device-faults")
    FaultResponse record(@RequestHeader(name = "Authorization", required = false) String authorization,
                         @RequestBody FaultRequest request) {
        if (request.roomId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        devices.verify(authorization, request.roomId(), request.deviceId());
        Recorded r = faults.record(request.toFault());
        return new FaultResponse(r.duplicate() ? "DUPLICATE" : "STORED", r.matched(), r.excused());
    }

    record AttemptRequest(String at, String code, String via) {
    }

    record FaultRequest(String eventId, Integer roomId, String deviceId, String kind, String startedAt,
                        String endedAt, List<AttemptRequest> attempts) {

        Fault toFault() {
            if (eventId == null || eventId.isBlank() || eventId.length() > 64
                    || deviceId == null || deviceId.isBlank() || deviceId.length() > 50
                    || (attempts != null && attempts.size() > MAX_ATTEMPTS)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            List<Attempt> tried = attempts == null ? List.of() : attempts.stream().map(a -> {
                if (a == null || a.code() == null || !a.code().matches("[0-9]{6}")) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST);
                }
                return new Attempt(time(a.at()), a.code(), parse(Via.class, a.via()));
            }).toList();
            return new Fault(eventId, roomId, deviceId, parse(Kind.class, kind), time(startedAt), time(endedAt), tried);
        }

        private static <E extends Enum<E>> E parse(Class<E> type, String value) {
            try {
                return Enum.valueOf(type, String.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }

        /** 센서와 같이 오프셋(+09:00) 없는 시각은 400 */
        private static Instant time(String value) {
            try {
                return OffsetDateTime.parse(String.valueOf(value)).toInstant();
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
    }

    record FaultResponse(String result, int matched, int excused) {
    }
}
