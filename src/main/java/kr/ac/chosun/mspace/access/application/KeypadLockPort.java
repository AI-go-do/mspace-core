package kr.ac.chosun.mspace.access.application;

import java.time.Instant;
import java.util.Optional;

/** 키패드 잠금 상태와 입력 기록. 구현은 adapter/out/persistence */
public interface KeypadLockPort {

    /**
     * 그 방 잠금 줄을 이 트랜잭션이 끝날 때까지 잡고 읽는다 (같은 방 입력을 한 줄로 세움). 없는 방이면 빈 값.
     */
    Optional<LockState> lockRoom(int roomId);

    void saveLock(int roomId, int failedCount, Instant lockedUntil);

    /** 이미 처리한 이벤트의 결과. 처음이면 빈 값 */
    Optional<Verdict> findEvent(String eventId);

    void saveEvent(String eventId, int roomId, String deviceId, Verdict verdict, Long reservationId);

    /** @param lockedUntil 이 시각 전이면 잠김. 잠긴 적 없으면 null */
    record LockState(int failedCount, Instant lockedUntil) {
    }
}
