package kr.ac.chosun.mspace.access.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.ac.chosun.mspace.access.application.Verdict;
import kr.ac.chosun.mspace.access.application.VerifyAccessService;
import kr.ac.chosun.mspace.common.auth.DeviceTokenVerifier;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문 앞 키패드가 부르는 입구 (FR-ACC-001). 사람 로그인 대신 장치 토큰(Authorization: Bearer)으로 막는다.
 * 실제 도어락·QR 리더도 같은 API를 부르면 된다 (AccessDevice 어댑터).
 */
@RestController
class AccessController {

    private final VerifyAccessService service;
    private final DeviceTokenVerifier devices;

    AccessController(VerifyAccessService service, DeviceTokenVerifier devices) {
        this.service = service;
        this.devices = devices;
    }

    /**
     * 예: POST /api/access/verify, Authorization: Bearer 장치토큰
     * {"eventId":"1-48213-18","roomId":1,"deviceId":"keypad-1","code":"123456"}
     * → 200 {"result":"OPENED"} 또는 {"result":"DENIED","reason":"WRONG_CODE"|"NOT_YET"|"LOCKED"}.
     * 두고 간 물건 찾으러 온 10분 QR(POST /api/reservations/{id}/retrieve-pass)이면 {"result":"OPENED","purpose":"RETRIEVE"}.
     * 거절도 200이다: 장치는 result만 보고 LED를 켠다. 401은 토큰 문제, 400은 요청 형식 문제.
     */
    @PostMapping("/api/access/verify")
    VerifyResponse verify(@RequestHeader(name = "Authorization", required = false) String authorization,
                          @RequestBody VerifyRequest request) {
        devices.verify(authorization, request.roomId(), request.deviceId());
        if (request.eventId() == null || request.eventId().isBlank() || request.eventId().length() > 64
                || request.roomId() == null || (request.deviceId() != null && request.deviceId().length() > 50)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Verdict v = service.verify(request.eventId(), request.roomId(), request.deviceId(), request.code());
        return new VerifyResponse(v.result(), v.reason(), v.purpose());
    }

    record VerifyRequest(String eventId, Integer roomId, String deviceId, String code) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record VerifyResponse(String result, String reason, String purpose) {
    }
}
