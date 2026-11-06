package kr.ac.chosun.mspace.tidy.adapter.in.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.DeviceTokenVerifier;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.LeftItem;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Recorded;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Result;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.RoomNow;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.TidyCheck;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 방 카메라(젯슨)가 부르는 입구 (FR-DEV-105). 키패드·센서와 같은 장치 토큰(Authorization: Bearer).
 * 형식: AI-go-do/docs api/README.md 6-3.
 */
@RestController
class TidyCheckController {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_ITEMS = 20;
    private static final int MAX_ITEM_LENGTH = 50;

    private final TidyCheckUseCase service;
    private final DeviceTokenVerifier devices;

    TidyCheckController(TidyCheckUseCase service, DeviceTokenVerifier devices) {
        this.service = service;
        this.devices = devices;
    }

    /** 예: GET /api/rooms/2/current → {roomId, current|null, lastEnded|null} */
    @GetMapping("/api/rooms/{roomId}/current")
    RoomNowResponse current(@RequestHeader(name = "Authorization", required = false) String authorization,
                            @PathVariable int roomId) {
        devices.verify(authorization);
        return RoomNowResponse.from(service.current(roomId));
    }

    /** 예: POST /api/rooms/2/tidy-checks → 200 {"result":"STORED","reportId":12} 또는 DUPLICATE */
    @PostMapping("/api/rooms/{roomId}/tidy-checks")
    TidyCheckResponse record(@RequestHeader(name = "Authorization", required = false) String authorization,
                             @PathVariable int roomId, @RequestBody TidyCheckRequest request) {
        devices.verify(authorization);
        Recorded r = service.record(request.toCheck(roomId));
        return new TidyCheckResponse(r.duplicate() ? "DUPLICATE" : "STORED", r.reportId());
    }

    record TidyCheckRequest(String eventId, Long reservationId, String deviceId, String result, List<String> items,
                            List<String> leftItems, String observedAt) {

        TidyCheck toCheck(int roomId) {
            if (eventId == null || eventId.isBlank() || eventId.length() > 64 || reservationId == null
                    || deviceId == null || deviceId.isBlank() || deviceId.length() > 50
                    || items == null || items.size() > MAX_ITEMS
                    || items.stream().anyMatch(i -> i == null || i.isBlank() || i.length() > MAX_ITEM_LENGTH)
                    || (leftItems != null && leftItems.size() > LeftItem.values().length)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            List<LeftItem> left = leftItems == null ? List.of() : leftItems.stream().map(i -> parse(LeftItem.class, i)).toList();
            return new TidyCheck(eventId, roomId, reservationId, deviceId, parse(Result.class, result), List.copyOf(items),
                    left, observed());
        }

        private static <E extends Enum<E>> E parse(Class<E> type, String value) {
            try {
                return Enum.valueOf(type, String.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }

        /** 센서와 같이 오프셋(+09:00) 없는 시각은 400 */
        private Instant observed() {
            try {
                return OffsetDateTime.parse(String.valueOf(observedAt)).toInstant();
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
    }

    record TidyCheckResponse(String result, Long reportId) {
    }

    record Current(long reservationId, String status, ZonedDateTime startAt, ZonedDateTime endAt) {
    }

    record Ended(long reservationId, String status, ZonedDateTime endedAt) {
    }

    record RoomNowResponse(int roomId, Current current, Ended lastEnded) {

        static RoomNowResponse from(RoomNow r) {
            Current c = r.current() == null ? null : new Current(r.current().reservationId(), r.current().status(),
                    r.current().startAt().atZone(SEOUL), r.current().endAt().atZone(SEOUL));
            Ended e = r.lastEnded() == null ? null : new Ended(r.lastEnded().reservationId(), r.lastEnded().status(),
                    r.lastEnded().endedAt().atZone(SEOUL));
            return new RoomNowResponse(r.roomId(), c, e);
        }
    }
}
