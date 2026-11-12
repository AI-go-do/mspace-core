package kr.ac.chosun.mspace.tidy.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Listed;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.PhotoRef;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 정리 확인 목록 (FR-DEV-105). 형식: AI-go-do/docs api/README.md 6-3 */
@RestController
class TidyCheckListController {

    private final TidyCheckUseCase service;

    TidyCheckListController(TidyCheckUseCase service) {
        this.service = service;
    }

    /** 예: GET /api/tidy-checks?days=7 → {"items":[{"reservationId":40,…,"phase":"FIRST","result":"LEFT_ITEMS","items":[{"name":"우산","confidence":0.93}]}]} */
    @GetMapping("/api/tidy-checks")
    ListResponse list(LoginUser user, @RequestParam(required = false) Integer days) {
        return new ListResponse(service.list(user.id(), days).stream().map(ItemResponse::from).toList());
    }

    record ListResponse(List<ItemResponse> items) {
    }

    /** confidence는 11/10 전 기록이면 null */
    record SeenItem(String name, Float confidence) {
    }

    /**
     * 관리자 화면 사진 (11/12 지영). url로 사진을 받는다: 카메라는 GET /api/reservations/{id}/tidy-photos/{phase},
     * 학생 사진은 GET /api/reservations/{id}/photos/{kind} (둘 다 관리자가 볼 수 있음)
     * @param source CAMERA 또는 STUDENT(카메라가 못 찍어서 학생이 올린 입실·퇴실 사진으로 대신)
     */
    record PhotoResponse(String source, ZonedDateTime takenAt, String url) {

        static PhotoResponse from(long reservationId, PhotoRef p) {
            if (p == null) {
                return null;
            }
            String path = "CAMERA".equals(p.source()) ? "tidy-photos" : "photos";
            return new PhotoResponse(p.source(), seoul(p.takenAt()),
                    "/api/reservations/%d/%s/%s".formatted(reservationId, path, p.kind()));
        }
    }

    /** @param before 입실 전 사진, after 이 단계(퇴실 뒤·3시간 뒤) 사진. 없으면 null → 화면은 "사진을 못 받았어요" */
    record ItemResponse(long reservationId, int roomId, String roomName, ZonedDateTime endedAt, String phase,
                        String result, List<SeenItem> items, List<String> leftItems, Long reportId,
                        ZonedDateTime observedAt, PhotoResponse before, PhotoResponse after) {

        static ItemResponse from(Listed l) {
            return new ItemResponse(l.reservationId(), l.roomId(), l.roomName(), seoul(l.endedAt()), l.phase().name(),
                    l.result().name(), l.items().stream().map(i -> new SeenItem(i.name(), i.confidence())).toList(),
                    l.leftItems().stream().map(Enum::name).toList(), l.reportId(), seoul(l.observedAt()),
                    PhotoResponse.from(l.reservationId(), l.before()), PhotoResponse.from(l.reservationId(), l.after()));
        }

    }

    private static ZonedDateTime seoul(Instant t) {
        return t == null ? null : t.atZone(SEOUL);
    }
}
