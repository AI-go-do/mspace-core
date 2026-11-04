package kr.ac.chosun.mspace.reservation.application;

import java.time.Duration;
import java.time.Instant;

/**
 * 이어진 사용 안의 연장 횟수 (FR-RSV-105 우회 막기). 연장하기와 예약 만들기가 같이 쓴다.
 * 구현은 adapter/out/persistence
 */
@FunctionalInterface
public interface UsageChainPort {

    /**
     * startAt에 시작하는 예약 바로 앞에서부터 거슬러 올라가며, 같은 대표자의 예약이 끝난 뒤 gap 안에 다음 예약이 시작했으면
     * 한 줄(이어진 사용)로 보고 그 안의 연장 횟수를 센다. 퇴실·자동 퇴실한(실제로 쓴) 예약만 센다
     */
    int extensionsBefore(long ownerId, Instant startAt, Duration gap);
}
