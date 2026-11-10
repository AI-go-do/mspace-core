package kr.ac.chosun.mspace.appeal.adapter.out.reviewer;

import kr.ac.chosun.mspace.appeal.application.WarningReviewer;
import kr.ac.chosun.mspace.common.config.TidyPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 판단기 고르기 (A-6). GEMINI_API_KEY가 있으면 Gemini, 없으면 규칙 판단기.
 * Gemini가 답하지 못할 때도 규칙 판단기가 대신 보므로, 철회 요청이 대기 상태로 오래 남지 않는다.
 */
@Configuration
class ReviewerConfig {

    private static final Logger log = LoggerFactory.getLogger(ReviewerConfig.class);

    @Bean
    WarningReviewer warningReviewer(TidyPolicy tidy, GeminiReviewerProperties gemini) {
        RuleWarningReviewer rules = new RuleWarningReviewer(tidy);
        if (!gemini.enabled()) {
            log.info("경고 철회 판단기: rules (GEMINI_API_KEY 없음)");
            return rules;
        }
        log.info("경고 철회 판단기: gemini {} (실패하면 rules)", gemini.model());
        return new GeminiWarningReviewer(gemini, tidy, rules);
    }
}
