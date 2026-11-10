package kr.ac.chosun.mspace.appeal.adapter.in.scheduler;

import kr.ac.chosun.mspace.appeal.application.AppealReviewUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1분마다 대기 중인 경고 철회 요청을 판단한다 (FR-RPT-105). 예약 정리와 같은 스위치(mspace.reservation.sweep.enabled)를
 * 따른다: 테스트는 꺼 두고 reviewPending()을 직접 부른다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mspace.reservation.sweep.enabled", havingValue = "true")
class AppealReviewScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppealReviewScheduler.class);

    private final AppealReviewUseCase reviews;

    AppealReviewScheduler(AppealReviewUseCase reviews) {
        this.reviews = reviews;
    }

    @Scheduled(fixedDelayString = "${mspace.appeal.review-interval}", initialDelayString = "${mspace.appeal.review-interval}")
    void review() {
        int done = reviews.reviewPending();
        if (done > 0) {
            log.info("경고 철회 요청 {}건 판단", done);
        }
    }
}
