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

    /**
     * 이 방에서 이 번호를 가진 끝나지 않은(BOOKED·IN_USE) 예약. 번호는 방 안에서 겹치지 않아서(one_active_code_per_room)
     * 많아야 한 건이다.
     * 찾은 예약 줄을 트랜잭션 끝까지 잠근다(FOR UPDATE): 키패드 처리 중에 취소·퇴실이 끼어들지 못하고 끝날 때까지 기다린다.
     * 그래서 트랜잭션 안에서 불러야 한다.
     */
    Optional<CodeMatch> findActiveByCode(int roomId, String accessCode);

    /** checkinDeadline: 입실 마감 (미리 예약 시작 + 15분, 즉시 시작 + 10분). 지나면 아직 입실 안 한 예약은 문을 안 연다 */
    record CodeMatch(long id, ReservationStatus status, Instant startAt, Instant endAt, Instant checkinDeadline) {
    }

    /**
     * 참여자 이름은 실명 그대로. 가리는 건 서비스가 한다.
     * checkinDeadline: 입실 마감 (DB 트리거가 채운 값 그대로)
     * cancelableUntil: 이 시각 전까지 대표자가 취소할 수 있다 (미리 예약은 시작, 즉시 시작은 누른 뒤 설정한 분, 입실 마감을 넘지 않음)
     */
    record Row(long id, int roomId, String roomName, Instant startAt, Instant endAt, ReservationStatus status,
               long ownerId, String accessCode, Instant checkinDeadline, Instant cancelableUntil,
               List<ParticipantRow> participants) {
    }

    record ParticipantRow(long userId, String name, boolean owner) {
    }

    record Access(boolean owner, ReservationStatus status, Instant startAt) {
    }
}
