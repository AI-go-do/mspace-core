package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;

/**
 * 방의 사과 (Q-19): 문 장치 오류 때문에 입실을 못 해 생긴 노쇼를 무른다.
 * 장치 오류 구간에 그 예약 번호가 눌린 기록(device_fault_attempt)이 있는 NO_SHOW만 대상이다.
 * 장치 오류를 받았을 때와 1분 정리 때 부른다 (오류가 노쇼 처리보다 먼저 와도 나중에 무르도록).
 */
public interface ExcuseNoShowsUseCase {

    /** 무른 예약 수. 이미 무른 예약은 다시 하지 않는다 (동시에 불려도 한 번만) */
    int excuse(Instant now);
}
