package kr.ac.chosun.mspace.report.adapter.out.judge;

import kr.ac.chosun.mspace.common.config.TidyPolicy;
import kr.ac.chosun.mspace.report.application.ReportJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 신고 판정기 고르기 (Q-28). GEMINI_API_KEY가 있으면 Gemini(창문 열림·어질러짐), 없으면 규칙 판정기.
 * Gemini가 답하지 못할 때도 규칙 판정기가 대신 보므로 신고가 대기로 오래 남지 않는다.
 */
@Configuration
class ReportJudgeConfig {

    private static final Logger log = LoggerFactory.getLogger(ReportJudgeConfig.class);

    @Bean
    ReportJudge reportJudge(TidyPolicy tidy, GeminiReportJudgeProperties gemini) {
        RuleReportJudge rules = new RuleReportJudge(tidy);
        if (!gemini.enabled()) {
            log.info("신고 판정기: rules (GEMINI_API_KEY 없음)");
            return rules;
        }
        log.info("신고 판정기: gemini {} (창문 열림·어질러짐, 실패하면 rules)", gemini.model());
        return new GeminiReportJudge(gemini, tidy, rules);
    }
}
