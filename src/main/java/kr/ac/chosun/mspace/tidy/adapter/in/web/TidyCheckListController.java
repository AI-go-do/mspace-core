package kr.ac.chosun.mspace.tidy.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Listed;
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

    record ItemResponse(long reservationId, int roomId, String roomName, ZonedDateTime endedAt, String phase,
                        String result, List<SeenItem> items, List<String> leftItems, Long reportId,
                        ZonedDateTime observedAt) {

        static ItemResponse from(Listed l) {
            return new ItemResponse(l.reservationId(), l.roomId(), l.roomName(), seoul(l.endedAt()), l.phase().name(),
                    l.result().name(), l.items().stream().map(i -> new SeenItem(i.name(), i.confidence())).toList(),
                    l.leftItems().stream().map(Enum::name).toList(), l.reportId(), seoul(l.observedAt()));
        }

        private static ZonedDateTime seoul(Instant t) {
            return t == null ? null : t.atZone(SEOUL);
        }
    }
}
