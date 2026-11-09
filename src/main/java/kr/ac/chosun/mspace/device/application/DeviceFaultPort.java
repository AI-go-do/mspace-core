package kr.ac.chosun.mspace.device.application;

import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.device.application.DeviceFaultUseCase.Attempt;
import kr.ac.chosun.mspace.device.application.DeviceFaultUseCase.Fault;

/** 장치 오류 저장. 구현은 adapter/out/persistence */
public interface DeviceFaultPort {

    /** 저장하고 id. 같은 eventId가 이미 있으면 빈 값 (동시에 와도 한 줄만) */
    Optional<Long> insert(Fault fault, Instant receivedAt);

    /**
     * 눌린 번호 한 번을 저장한다. 같은 방, 그 번호, 번호가 맞을 수 있던 시간(시작 openBeforeMinutes분 전 ~ 입실 마감·끝 중 이른 쪽)
     * 안의 취소 안 된 예약을 찾아 id만 남긴다. 번호는 저장하지 않는다
     *
     * @return 예약을 찾았으면 true
     */
    boolean insertAttempt(long faultId, int roomId, Attempt attempt, int openBeforeMinutes);

    /** 이 방 장치 오류로 since 뒤에 받은 번호 수 (방별 하루 상한) */
    int attemptsSince(int roomId, Instant since);

    /** 방별 하루 상한을 세는 동안 잡는 트랜잭션 잠금. 예약 잠금(-방·회원 번호)과 다른 키 공간 */
    void lockRoomCount(int roomId);
}
