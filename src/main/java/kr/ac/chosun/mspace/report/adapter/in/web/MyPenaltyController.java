package kr.ac.chosun.mspace.report.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.ZonedDateTime;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.report.application.MyPenaltyUseCase.MyPenalty;
import kr.ac.chosun.mspace.report.application.MyPenaltyUseCase;
import kr.ac.chosun.mspace.reservation.application.MyNoShowsUseCase.Summary;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 경고 횟수 (FR-WEB-003 이용 규칙 화면 위쪽 "내 경고 0회"). 로그인한 본인 것만 */
@RestController
class MyPenaltyController {

    private final MyPenaltyUseCase penalties;

    MyPenaltyController(MyPenaltyUseCase penalties) {
        this.penalties = penalties;
    }

    /**
     * 예: GET /api/penalties/me → 200 {"warnings":1,"falseReportWarnings":0,"limit":3,"windowDays":30,
     * "suspendedUntil":null,"reportSuspendedUntil":null,
     * "noShows":{"noShows":1,"awayReleases":0,"limit":2,"awayPerNoShow":3,"windowDays":30}}. 로그인 안 하면 401.
     * 정지(suspendedUntil)는 경고·노쇼 어느 쪽으로 걸렸든 같은 칸
     */
    @GetMapping("/api/penalties/me")
    ResponseEntity<MyPenaltyResponse> mine(LoginUser user) {
        MyPenalty p = penalties.mine(user.id());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MyPenaltyResponse(p.warnings(), p.falseReportWarnings(), p.limit(), p.windowDays(),
                seoul(p.suspendedUntil()), seoul(p.reportSuspendedUntil()), p.noShows()));
    }

    private static ZonedDateTime seoul(Instant t) {
        return t == null ? null : t.atZone(SEOUL);
    }

    record MyPenaltyResponse(int warnings, int falseReportWarnings, int limit, int windowDays,
                             ZonedDateTime suspendedUntil, ZonedDateTime reportSuspendedUntil, Summary noShows) {
    }
}
