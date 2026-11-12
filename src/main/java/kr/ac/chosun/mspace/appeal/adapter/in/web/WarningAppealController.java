package kr.ac.chosun.mspace.appeal.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.appeal.application.WarningAppealUseCase.Appeal;
import kr.ac.chosun.mspace.appeal.application.WarningAppealUseCase.WarningItem;
import kr.ac.chosun.mspace.appeal.application.WarningAppealUseCase;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 내 경고 목록과 경고 철회 요청 (FR-RPT-105, docs api 6-1). 로그인한 본인 것만 */
@RestController
class WarningAppealController {

    private final WarningAppealUseCase appeals;

    WarningAppealController(WarningAppealUseCase appeals) {
        this.appeals = appeals;
    }

    /** 예: GET /api/penalties/me/warnings?days=30 → 200 [ {id, reason, reportType, …, appealableUntil, appeal} ] */
    @GetMapping("/api/penalties/me/warnings")
    ResponseEntity<List<WarningResponse>> mine(LoginUser user, @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(appeals.mine(user.id(), days).stream().map(WarningResponse::of).toList());
    }

    /** 예: POST /api/warnings/41/appeal {"text":"퇴실할 때 컵은 다 버렸어요.","evidence":["CHECKOUT_PHOTO"]} → 201 목록 한 줄 */
    @PostMapping("/api/warnings/{id}/appeal")
    @ResponseStatus(HttpStatus.CREATED)
    WarningResponse appeal(LoginUser user, @PathVariable long id, @RequestBody AppealRequest request) {
        return WarningResponse.of(appeals.appeal(user.id(), id, request.text(), request.evidence()));
    }

    record AppealRequest(String text, List<String> evidence) {
    }

    record WarningResponse(long id, String reason, String reportType, int roomId, Long reservationId,
                           ZonedDateTime startAt, ZonedDateTime endAt, ZonedDateTime createdAt, String why,
                           ZonedDateTime appealableUntil, AppealResponse appeal) {

        static WarningResponse of(WarningItem w) {
            return new WarningResponse(w.id(), w.reason(), w.reportType(), w.roomId(), w.reservationId(),
                    seoul(w.startAt()), seoul(w.endAt()), seoul(w.createdAt()), w.why(), seoul(w.appealableUntil()),
                    AppealResponse.of(w.appeal()));
        }
    }

    record AppealResponse(String status, String text, ZonedDateTime createdAt, ZonedDateTime decidedAt, String answer) {

        static AppealResponse of(Appeal a) {
            return a == null ? null : new AppealResponse(a.status(), a.text(), seoul(a.createdAt()), seoul(a.decidedAt()), a.answer());
        }
    }

    private static ZonedDateTime seoul(Instant t) {
        return t == null ? null : t.atZone(SEOUL);
    }
}
