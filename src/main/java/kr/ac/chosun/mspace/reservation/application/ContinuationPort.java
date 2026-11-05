package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;

/** 연장하기(FR-RSV-105)에 필요한 DB 일. 구현은 adapter/out/persistence */
public interface ContinuationPort {

    /** 연장할 예약. 없으면 빈 값 */
    Optional<ContinueTarget> findContinueTarget(long reservationId);

    /** 이 예약이 어느 예약에서 연장됐나. 연장이 아니면 빈 값 */
    Optional<Long> continuedFrom(long reservationId);

    /** participantIds에는 대표자도 들어 있다 */
    record ContinueTarget(long id, int roomId, long ownerId, Instant startAt, Instant endAt,
                          ReservationStatus status, Long continuedFromId, List<Long> participantIds) {
    }
}
