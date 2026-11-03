package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;

/** 예약 읽기용 DB 일. 구현은 adapter/out/persistence */
public interface ReservationQueryPort {

    /** 회원이 대표자이거나 참여자인 예약 (순서는 서비스가 정한다) */
    List<Row> findByMember(long userId);

    /**
     * 예약 하나를 이 회원 입장에서 본다. 예약이 없거나 이 회원이 참여자가 아니면 빈 값.
     * 취소가 안 됐을 때 404·403·409 중 무엇인지 고르는 데 쓴다.
     */
    Optional<Access> findAccess(long reservationId, long userId);

    /** 참여자 이름은 실명 그대로. 가리는 건 서비스가 한다 */
    record Row(long id, int roomId, String roomName, Instant startAt, Instant endAt, ReservationStatus status,
               long ownerId, String accessCode, List<ParticipantRow> participants) {
    }

    record ParticipantRow(long userId, String name, boolean owner) {
    }

    record Access(boolean owner, ReservationStatus status, Instant startAt) {
    }
}
