package kr.ac.chosun.mspace.modelscore.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.DeviceTokenVerifier;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.modelscore.application.ModelScoreUseCase;
import kr.ac.chosun.mspace.modelscore.application.ModelScoreUseCase.Kind;
import kr.ac.chosun.mspace.modelscore.application.ModelScoreUseCase.Score;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 방 카메라 모델 시험 점수 (11/12 재웅·지영). 받기는 방 카메라 장치 토큰(tidy-checks와 같음), 보기는 관리자만.
 * 관리자 화면은 measuredAt이 가장 늦은 것 하나를 "지금 모델 정확도"로 쓴다. 비어 있으면 화면이 적어 둔 값을 보여 준다
 */
@RestController
class ModelScoreController {

    private static final int MAX_EVENT_ID = 64;
    private static final int MAX_MODEL = 60;
    private static final int MAX_DEVICE_ID = 50;

    private final ModelScoreUseCase service;
    private final DeviceTokenVerifier devices;

    ModelScoreController(ModelScoreUseCase service, DeviceTokenVerifier devices) {
        this.service = service;
        this.devices = devices;
    }

    /** 예: POST /api/rooms/1/model-scores → 200 {"result":"STORED"} 또는 {"result":"DUPLICATE"} */
    @PostMapping("/api/rooms/{roomId}/model-scores")
    SubmitResponse submit(@RequestHeader(name = "Authorization", required = false) String authorization,
                          @PathVariable int roomId, @RequestBody ScoreRequest request) {
        devices.verify(authorization, roomId, request.deviceId());
        boolean duplicate = service.submit(request.toScore(roomId));
        return new SubmitResponse(duplicate ? "DUPLICATE" : "STORED");
    }

    /** 예: GET /api/admin/model-scores?kind=TIDY → {"items":[{"kind":"TIDY","model":"yolov8s","accuracy":0.766,…}]} */
    @GetMapping("/api/admin/model-scores")
    ResponseEntity<ListResponse> list(LoginUser user, @RequestParam(required = false) String kind) {
        // 관리자만 보는 값이라 브라우저 뒤로 가기로 다른 계정에 남지 않게 (MyPenaltyController와 같음)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ListResponse(service.recent(user.id(), kind(kind == null ? "TIDY" : kind)).stream()
                        .map(ItemResponse::from).toList()));
    }

    private static Kind kind(String value) {
        try {
            return Kind.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "kind는 TIDY여야 합니다");
        }
    }

    record SubmitResponse(String result) {
    }

    /** accuracy·accuracy3는 0~1 소수(76.6%면 0.766). accuracy3·serious·deviceId는 없어도 됨 */
    record ScoreRequest(String eventId, String kind, String model, Double accuracy, Double accuracy3, Integer cases,
                        Integer serious, Double medianSeconds, String measuredAt, String deviceId) {

        Score toScore(int roomId) {
            if (blankOrLonger(eventId, MAX_EVENT_ID) || blankOrLonger(model, MAX_MODEL)
                    || (deviceId != null && blankOrLonger(deviceId, MAX_DEVICE_ID))) {
                throw invalid("eventId·model은 비울 수 없고 길이 제한(64·60자)이 있습니다");
            }
            if (kind == null) {
                throw invalid("kind는 TIDY여야 합니다");
            }
            Kind k = ModelScoreController.kind(kind);
            if (!ratio(accuracy) || (accuracy3 != null && !ratio(accuracy3))) {
                throw invalid("accuracy·accuracy3는 0~1 사이 소수여야 합니다(76.6%면 0.766)");
            }
            if (cases == null || cases < 1 || (serious != null && serious < 0)) {
                throw invalid("cases는 1 이상, serious는 0 이상이어야 합니다");
            }
            // float로 줄인 뒤에도 유한해야 한다(1e100은 double로는 유한이지만 float로는 무한대)
            if (medianSeconds == null || medianSeconds < 0 || !Float.isFinite(medianSeconds.floatValue())) {
                throw invalid("medianSeconds는 0 이상이어야 합니다");
            }
            return new Score(eventId, k, model, accuracy.floatValue(),
                    accuracy3 == null ? null : accuracy3.floatValue(), cases, serious, medianSeconds.floatValue(),
                    instant(measuredAt), roomId, deviceId);
        }

        private static boolean blankOrLonger(String s, int max) {
            return s == null || s.isBlank() || s.length() > max;
        }

        private static boolean ratio(Double v) {
            return v != null && v >= 0 && v <= 1;
        }

        /** 오프셋 없는 시각은 받지 않는다(+09:00 규칙) */
        private static Instant instant(String value) {
            if (value == null) {
                throw invalid("measuredAt이 필요합니다");
            }
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException e) {
                throw invalid("measuredAt은 2026-11-11T21:00:00+09:00 형식이어야 합니다");
            }
        }

        private static BusinessException invalid(String message) {
            return new BusinessException(ErrorCode.INVALID_REQUEST, message);
        }
    }

    record ListResponse(List<ItemResponse> items) {
    }

    /** accuracy3·serious는 보낸 적 없으면 null */
    record ItemResponse(String kind, String model, float accuracy, Float accuracy3, int cases, Integer serious,
                        float medianSeconds, ZonedDateTime measuredAt) {

        static ItemResponse from(Score s) {
            return new ItemResponse(s.kind().name(), s.model(), s.accuracy(), s.accuracy3(), s.cases(), s.serious(),
                    s.medianSeconds(), s.measuredAt().atZone(SEOUL));
        }
    }
}
