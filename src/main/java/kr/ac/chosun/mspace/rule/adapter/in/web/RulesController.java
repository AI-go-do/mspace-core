package kr.ac.chosun.mspace.rule.adapter.in.web;

import kr.ac.chosun.mspace.rule.application.RulesService;
import kr.ac.chosun.mspace.rule.application.RulesService.Rules;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 이용 규칙 API (FR-WEB-003). 로그인 전에도 볼 수 있다 (수용 기준) */
@RestController
class RulesController {

    private final RulesService rules;

    RulesController(RulesService rules) {
        this.rules = rules;
    }

    /**
     * 예: GET /api/rules → {"reservation":{"bookingWindowDays":7,"slotMinutes":30,"maxMinutes":120,...},
     * "conduct":[{"key":"FOOD","title":"음식","rule":"음식물 반입 금지예요...","penalty":"..."},...],
     * "reminders":{"checkin":"음식물은 반입 금지예요","checkout":"자리 정리하셨나요?"}}
     */
    @GetMapping("/api/rules")
    Rules rules() {
        return rules.rules();
    }
}
