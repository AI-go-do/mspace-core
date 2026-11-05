package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예약 알림 시각 (FR-NTF-101, 102, 104). 코드에 박지 않고 application.yml에서 읽는다.
 *
 * @param startBeforeMinutes  미리 예약 시작 몇 분 전에 "입실 N분 전입니다" (대표자·참여자 전원, 즉시 시작은 안 보냄)
 * @param endBeforeMinutes    끝나기 몇 분 전에 "종료 N분 전입니다" (사용 중인 예약, 전원)
 * @param noShowBeforeMinutes 입실 마감 몇 분 전에 "노쇼 N분 전입니다" (아직 입실 안 한 예약, 대표자만)
 */
@ConfigurationProperties("mspace.notification.reminder")
public record ReminderPolicy(int startBeforeMinutes, int endBeforeMinutes, int noShowBeforeMinutes) {
}
