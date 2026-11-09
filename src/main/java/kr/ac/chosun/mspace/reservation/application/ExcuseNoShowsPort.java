package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.List;

/** 노쇼 무르기 DB 일. 구현은 adapter/out/persistence */
public interface ExcuseNoShowsPort {

    /**
     * 장치 오류 중 번호가 눌린 기록이 있는, 아직 안 무른 NO_SHOW 예약을 무른다(no_show_excused_at = now).
     * 조건부 UPDATE라 동시에 불려도 한 쪽만 바꾼다
     */
    List<Excused> excuseMatched(Instant now);

    /**
     * 이 노쇼 뒤에 걸린 노쇼 정지(지금 진행 중이거나 지금 시작)를 푼다. 진행 중이면 끝을 지금으로, 지금 시작이면 지운다
     *
     * @param noShowAt 노쇼 시각 (입실 마감과 끝 중 이른 쪽)
     * @return 푼 정지가 있으면 true
     */
    boolean liftSuspension(long ownerId, Instant noShowAt, Instant now);

    /** 예약의 대표자·참여자 회원 id */
    List<Long> members(long reservationId);

    /** @param noShowAt 입실 마감과 끝 중 이른 쪽 */
    record Excused(long reservationId, long ownerId, String roomName, Instant startAt, Instant noShowAt) {
    }
}
