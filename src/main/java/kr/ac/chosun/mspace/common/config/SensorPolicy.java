package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재실·CO2 센서 숫자 (FR-DEV-101~104, 11/5 지영·수연 확정). 코드에 박지 않고 application.yml에서 읽는다.
 *
 * @param offlineAfterSeconds   재실 신호가 이만큼 안 오면 끊김 → 그 방은 "있음"으로 본다 (60, 10초 신호 6번)
 * @param futureToleranceMinutes observedAt이 서버 시각보다 이만큼 넘게 미래면 400 (장치 시계 오류, 5)
 * @param away                  자리 비움 (FR-DEV-103)
 * @param co2                   환기 알림 (FR-DEV-104)
 */
@ConfigurationProperties("mspace.sensor")
public record SensorPolicy(int offlineAfterSeconds, int futureToleranceMinutes, Away away, Co2 co2) {

    /**
     * @param notifyAfterMinutes  사용 중 비어 있음이 이만큼이면 대표자에게 "5분 안에 돌아와 주세요" (10)
     * @param releaseAfterMinutes 이만큼이면 자동 퇴실 AWAY_RELEASED (15)
     * @param perNoShow           30일 안 자리 비움 몇 번이 노쇼 1번인가 (3)
     * @param stillHereEnabled    "아직 있어요" 버튼을 켤지 (기본 true. 11/5 실제 서비스에도 넣기로 함, FR-DEV-103)
     */
    public record Away(int notifyAfterMinutes, int releaseAfterMinutes, int perNoShow, boolean stillHereEnabled) {
    }

    /**
     * @param thresholdPpm      이 값을 넘으면 높음 (1000)
     * @param consecutive       높은 값이 몇 번 연속이면 알림 (3, 1분마다 → 3분 연속)
     * @param minGapSeconds     이웃한 값 간격이 이보다 짧으면 연속으로 안 셈 (30)
     * @param maxGapSeconds     이보다 길면 끊긴 것으로 보고 처음부터 (90)
     * @param minSpanSeconds    첫 값과 마지막 값이 이만큼은 떨어져야 함 (100)
     * @param alertEveryMinutes 같은 방 알림 간격 (30)
     */
    public record Co2(int thresholdPpm, int consecutive, int minGapSeconds, int maxGapSeconds, int minSpanSeconds,
                      int alertEveryMinutes) {
    }
}
