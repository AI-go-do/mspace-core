package kr.ac.chosun.mspace.room.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.Block;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.Conflict;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.NewBlock;

/** 기간 막기 DB 일. 구현은 adapter/out/persistence */
public interface RoomBlockPort {

    boolean isAdmin(long userId);

    /** 예약 저장과 같은 방 잠금을 잡는다. 막기와 학생 예약이 동시에 와도 한 줄로 서게 */
    void lockRoom(int roomId);

    /** 저장. 같은 방의 풀리지 않은 막기와 겹치면 409 ROOM_BLOCK_OVERLAP */
    Block insert(long adminId, NewBlock block, Instant now);

    List<Block> findEndingAfter(Instant from);

    Optional<Block> find(long blockId);

    /** 풀리지 않았으면 푼다. 풀었으면 true */
    boolean release(long blockId, long adminId, Instant now);

    /** 같은 방·겹치는 시간의 끝나지 않은(BOOKED·IN_USE) 예약. 시작 빠른 순 */
    List<Conflict> conflicts(int roomId, Instant startAt, Instant endAt, Instant now);

    /** 같은 방·겹치는 시간에서 아직 시작 전인 BOOKED 예약을 취소한다. 취소한 예약 id */
    List<Long> cancelBooked(int roomId, Instant startAt, Instant endAt, Instant now);

    /** 예약의 대표자·참여자 회원 id */
    List<Long> members(long reservationId);

    String roomName(int roomId);
}
