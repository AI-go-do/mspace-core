package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * 이용 정지 조회 (FR-RSV-106 노쇼 벌칙). 예약 만들기가 대표자·참여자를 막을 때 쓴다.
 * 정지를 거는 쪽은 노쇼 정리({@link ReservationSweepPort#markNoShows})다. 구현은 adapter/out/persistence
 */
public interface SuspensionPort {

    /** 지금 정지 중이면 정지가 끝나는 시각 */
    Optional<Instant> suspendedUntil(long userId, Instant now);

    /** 주어진 회원 중 지금 정지 중인 사람이 있나 */
    boolean anySuspended(Collection<Long> userIds, Instant now);
}
