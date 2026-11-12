package kr.ac.chosun.mspace.reservation.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.reservation.application.MyNoShowsUseCase;
import kr.ac.chosun.mspace.reservation.application.MyNoShowsUseCase.Item;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내 노쇼 기록 (FR-RSV-106, 학생 "내 경고" 화면). 로그인한 본인이 대표자인 것만 */
@RestController
class MyNoShowsController {

    private final MyNoShowsUseCase service;

    MyNoShowsController(MyNoShowsUseCase service) {
        this.service = service;
    }

    /**
     * 예: GET /api/penalties/me/no-shows?days=30 → {"items":[{"reservationId":40,"roomId":1,"roomName":"1번 방",
     * "startAt":"…+09:00","endAt":"…+09:00","kind":"NO_SHOW","happenedAt":"…+09:00","counted":true,"excusedAt":null}]}
     */
    @GetMapping("/api/penalties/me/no-shows")
    ListResponse mine(LoginUser user, @RequestParam(required = false) Integer days) {
        return new ListResponse(service.list(user.id(), days).stream().map(ItemResponse::from).toList());
    }

    record ListResponse(List<ItemResponse> items) {
    }

    record ItemResponse(long reservationId, int roomId, String roomName, ZonedDateTime startAt, ZonedDateTime endAt,
                        String kind, ZonedDateTime happenedAt, boolean counted, ZonedDateTime excusedAt) {

        static ItemResponse from(Item i) {
            return new ItemResponse(i.reservationId(), i.roomId(), i.roomName(), seoul(i.startAt()), seoul(i.endAt()),
                    i.kind(), seoul(i.happenedAt()), i.counted(), seoul(i.excusedAt()));
        }
    }

    private static ZonedDateTime seoul(Instant t) {
        return t == null ? null : t.atZone(SEOUL);
    }
}
