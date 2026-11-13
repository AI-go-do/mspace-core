package kr.ac.chosun.mspace.ops.adapter.in.web;

import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.ops.application.AiScoreUseCase;
import kr.ac.chosun.mspace.ops.application.AiScoreUseCase.Scores;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 "AI 수습사원" 카드 점수 (11/13 지영). 화면이 몇 초마다 불러 늘어나면 올라가게 보여 준다 */
@RestController
class AiScoresController {

    private final AiScoreUseCase scores;

    AiScoresController(AiScoreUseCase scores) {
        this.scores = scores;
    }

    /**
     * 예: GET /api/admin/ai-scores → {"at":"…+09:00","items":[{"kind":"VENT_SOON","right":97,"total":100,
     * "base":{"right":96,"total":99},"live":{"right":1,"total":1,"pending":0}}, …]}. 관리자가 아니면 403
     */
    @GetMapping("/api/admin/ai-scores")
    Scores scores(LoginUser user) {
        return scores.scores(user.id());
    }
}
