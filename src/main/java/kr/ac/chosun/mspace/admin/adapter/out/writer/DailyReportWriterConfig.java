package kr.ac.chosun.mspace.admin.adapter.out.writer;

import kr.ac.chosun.mspace.admin.application.DailyReportWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 하루 운영 보고 쓰는 쪽 고르기. GEMINI_API_KEY가 있으면 Gemini, 없으면 정해진 틀. Gemini가 실패해도 틀로 쓴다 */
@Configuration
class DailyReportWriterConfig {

    private static final Logger log = LoggerFactory.getLogger(DailyReportWriterConfig.class);

    @Bean
    DailyReportWriter dailyReportWriter(GeminiDailyReportProperties gemini) {
        TemplateDailyReportWriter template = new TemplateDailyReportWriter();
        if (!gemini.enabled()) {
            log.info("하루 운영 보고: template (GEMINI_API_KEY 없음)");
            return template;
        }
        log.info("하루 운영 보고: gemini {} (실패하면 template)", gemini.model());
        return new GeminiDailyReportWriter(gemini, template);
    }
}
