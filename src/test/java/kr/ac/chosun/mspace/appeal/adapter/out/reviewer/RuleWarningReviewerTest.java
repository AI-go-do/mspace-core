package kr.ac.chosun.mspace.appeal.adapter.out.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Case;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Decision;
import kr.ac.chosun.mspace.appeal.application.WarningReviewer.Review;
import kr.ac.chosun.mspace.common.config.TidyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 규칙 판단기: 방 비품은 빼고 남은 물건 확신도로 본다 (rules-v2, 11/11 정확도 측정) */
class RuleWarningReviewerTest {

    final RuleWarningReviewer rules = new RuleWarningReviewer(new TidyPolicy(180, 0.8, 10));

    static Case camera(List<String> items, List<Double> conf) {
        return new Case(1, "VIOLATION", "CAMERA", "MESSY", null, "다시 봐 주세요.", List.of(), false, false, items, conf);
    }

    @Test
    @DisplayName("방 비품(의자·책상)만 남았으면 확신도가 높아도 철회")
    void fixturesOnlyWithdrawn() {
        // when
        Review r = rules.review(camera(List.of("chair", "Desk"), List.of(0.99, 0.97)));

        // then
        assertThat(r.decision()).isEqualTo(Decision.WITHDRAWN);
        assertThat(r.promptVersion()).isEqualTo("rules-v2");
    }

    @Test
    @DisplayName("비품과 함께 기준 이상 개인 물건이 남았으면 그 물건 확신도로 유지")
    void personalItemKept() {
        // when
        Review r = rules.review(camera(List.of("chair", "tumbler"), List.of(0.99, 0.9)));

        // then
        assertThat(r.decision()).isEqualTo(Decision.KEPT);
        assertThat(r.confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("비품 말고 남은 물건이 기준 아래면 철회")
    void personalItemBelowWithdrawn() {
        // when
        Review r = rules.review(camera(List.of("chair", "laptop"), List.of(0.99, 0.6)));

        // then
        assertThat(r.decision()).isEqualTo(Decision.WITHDRAWN);
    }
}
