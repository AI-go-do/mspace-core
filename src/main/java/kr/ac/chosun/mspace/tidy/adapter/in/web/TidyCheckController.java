package kr.ac.chosun.mspace.tidy.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.DeviceTokenVerifier;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Item;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Phase;
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

    private static final int MAX_ITEMS = 20;
    private static final int MAX_ITEM_LENGTH = 50;

    private final TidyCheckUseCase service;
    private final DeviceTokenVerifier devices;

    TidyCheckController(TidyCheckUseCase service, DeviceTokenVerifier devices) {
        this.service = service;
        this.devices = devices;
    }

    /** 예: GET /api/rooms/2/current → {roomId, current|null, lastEnded|null, recheck|null} */
    @GetMapping("/api/rooms/{roomId}/current")
    RoomNowResponse current(@RequestHeader(name = "Authorization", required = false) String authorization,
                            @PathVariable int roomId) {
        devices.verify(authorization, roomId, null);
        return RoomNowResponse.from(service.current(roomId));
    }

    /** 예: POST /api/rooms/2/tidy-checks → 200 {"result":"STORED","reportId":null} 또는 DUPLICATE */
    @PostMapping("/api/rooms/{roomId}/tidy-checks")
    TidyCheckResponse submit(@RequestHeader(name = "Authorization", required = false) String authorization,
                             @PathVariable int roomId, @RequestBody TidyCheckRequest request) {
        devices.verify(authorization, roomId, request.deviceId());
        Recorded r = service.submit(request.toCheck(roomId));
        return new TidyCheckResponse(r.duplicate() ? "DUPLICATE" : "STORED", r.reportId());
    }

    /** 물건 하나: {"name":"우산","confidence":0.93} */
    record ItemRequest(String name, Float confidence) {

        boolean valid() {
            return name != null && !name.isBlank() && name.length() <= MAX_ITEM_LENGTH
                    && confidence != null && confidence >= 0 && confidence <= 1;
        }
    }

    /**
     * phase는 없으면 FIRST. result는 CLEAN(남은 것 없음, items 빈 배열) 또는 LEFT_ITEMS(items 1개 이상).
     * 11/10 전 형식의 NEEDS_CLEANING은 400, leftItems는 받지 않는다(보내도 무시).
     */
    record TidyCheckRequest(String eventId, Long reservationId, String deviceId, String phase, String result,
                            List<ItemRequest> items, String observedAt) {

        TidyCheck toCheck(int roomId) {
            if (eventId == null || eventId.isBlank() || eventId.length() > 64 || reservationId == null
                    || deviceId == null || deviceId.isBlank() || deviceId.length() > 50
                    || items == null || items.size() > MAX_ITEMS
                    || items.stream().anyMatch(i -> i == null || !i.valid())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            Result r = parse(Result.class, result);
            if (r == Result.NEEDS_CLEANING || (r == Result.LEFT_ITEMS) == items.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            Phase p = phase == null ? Phase.FIRST : parse(Phase.class, phase);
            return new TidyCheck(eventId, roomId, reservationId, deviceId, p, r,
                    items.stream().map(i -> new Item(i.name(), i.confidence())).toList(), observed());
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

    record RecheckResponse(long reservationId, ZonedDateTime at) {
    }

    record RoomNowResponse(int roomId, Current current, Ended lastEnded, RecheckResponse recheck) {

        static RoomNowResponse from(RoomNow r) {
            Current c = r.current() == null ? null : new Current(r.current().reservationId(), r.current().status(),
                    r.current().startAt().atZone(SEOUL), r.current().endAt().atZone(SEOUL));
            Ended e = r.lastEnded() == null ? null : new Ended(r.lastEnded().reservationId(), r.lastEnded().status(),
                    r.lastEnded().endedAt().atZone(SEOUL));
            RecheckResponse again = r.recheck() == null ? null
                    : new RecheckResponse(r.recheck().reservationId(), r.recheck().at().atZone(SEOUL));
            return new RoomNowResponse(r.roomId(), c, e, again);
        }
    }
}
