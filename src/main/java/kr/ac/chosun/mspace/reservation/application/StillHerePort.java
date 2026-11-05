package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;

/** "아직 있어요"(FR-DEV-103)의 DB 일. 구현은 adapter/out/persistence */
public interface StillHerePort {

    /**
     * 이 회원이 대표자이고 IN_USE이고 아직 "아직 있어요"를 안 썼을 때만 still_here_at을 now로 둔다.
     * 자리 비움 타이머가 이 시각부터 다시 돈다. 조건부 UPDATE라 두 번 눌러도 한 번만.
     *
     * @return 바꿨으면 true
     */
    boolean markIfInUse(long reservationId, long ownerId, Instant now);
}
