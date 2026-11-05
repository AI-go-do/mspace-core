package kr.ac.chosun.mspace.photo.adapter.in.scheduler;

import kr.ac.chosun.mspace.photo.application.PhotoUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1시간마다 올린 지 30일 지난 사진을 지운다 (FR-RPT-102). 예약 정리와 같은 스위치(mspace.reservation.sweep.enabled)를
 * 따른다: 테스트는 꺼 두고 deleteExpired()를 직접 부른다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mspace.reservation.sweep.enabled", havingValue = "true")
class PhotoCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(PhotoCleanupScheduler.class);

    private final PhotoUseCase photos;

    PhotoCleanupScheduler(PhotoUseCase photos) {
        this.photos = photos;
    }

    @Scheduled(fixedDelayString = "${mspace.photo.cleanup-interval}",
            initialDelayString = "${mspace.photo.cleanup-interval}")
    void cleanup() {
        int deleted = photos.deleteExpired();
        if (deleted > 0) {
            log.info("보관 기간 지난 사진 {}장 지움", deleted);
        }
    }
}
