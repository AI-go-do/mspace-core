package kr.ac.chosun.mspace.admin.adapter.in.web;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.admin.application.PenaltyListUseCase;
import kr.ac.chosun.mspace.admin.application.PenaltyListUseCase.Member;
import kr.ac.chosun.mspace.admin.application.PenaltyListUseCase.Penalties;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 경고·정지 목록 API (FR-WEB-101). 관리자만 */
@RestController
class PenaltyListController {

    private final PenaltyListUseCase penalties;

    PenaltyListController(PenaltyListUseCase penalties) {
        this.penalties = penalties;
    }

    /**
     * 최근 days일(기본 30, 1–365) 안의 경고와, 그 사이 끝났거나 진행 중인 정지. 최근 것부터.
     * 예: GET /api/penalties?days=30. days가 틀리면 400, 관리자가 아니면 403
     */
    @GetMapping("/api/penalties")
    PenaltiesResponse list(LoginUser user, @RequestParam(required = false) Integer days) {
        Penalties p = penalties.list(user.id(), days);
        return new PenaltiesResponse(p.days(),
                p.warnings().stream().map(w -> new WarningResponse(w.id(), w.member(), w.reason(), w.reportId(),
                        w.reservationId(), seoul(w.createdAt()),
                        w.withdrawnAt() == null ? null : seoul(w.withdrawnAt()))).toList(),
                p.suspensions().stream().map(s -> new SuspensionResponse(s.scope(), s.id(), s.member(), s.reason(),
                        seoul(s.startsAt()), seoul(s.endsAt()), s.active())).toList());
    }

    private static ZonedDateTime seoul(Instant t) {
        return t.atZone(SEOUL);
    }

    record PenaltiesResponse(int days, List<WarningResponse> warnings, List<SuspensionResponse> suspensions) {
    }

    record WarningResponse(long id, Member member, String reason, long reportId, Long reservationId,
                           ZonedDateTime createdAt, ZonedDateTime withdrawnAt) {
    }

    record SuspensionResponse(String scope, long id, Member member, String reason, ZonedDateTime startsAt,
                              ZonedDateTime endsAt, boolean active) {
    }
}
