package kr.ac.chosun.mspace.device.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 장치 오류 받기 (Q-19). 오류·번호 저장은 한 트랜잭션, 노쇼 무르기는 커밋 뒤 응답과 따로({@link ExcuseKicker})
 * (무르기가 실패해도 오류 기록은 남고, 1분 정리가 다시 무른다).
 */
@Service
class DeviceFaultService implements DeviceFaultUseCase {

    /** 한 번에 받는 오류 구간 상한. 젯슨이 하루 넘게 꺼져 있었으면 나눠 보낸다 */
    static final Duration MAX_PERIOD = Duration.ofHours(24);

    /** 장치 시계가 조금 빨라도 받는다 */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    /**
     * 방마다 하루(24시간)에 받는 번호 수 상한. 장치 토큰이 새도 eventId를 바꿔 가며 번호를 끝없이 넣어 보지 못하게
     * (키패드 5번 잠금을 거치지 않는 길이라서, CodeRabbit #99). 정상이면 하루 오류 구간 동안 눌린 수십 번 안팎
     */
    static final int MAX_ATTEMPTS_PER_ROOM_PER_DAY = 200;

    private final DeviceFaultPort port;
    private final AccessPolicy access;
    private final TransactionOperations tx;
    private final Clock clock;
    private final ExcuseKicker kicker;

    DeviceFaultService(DeviceFaultPort port, AccessPolicy access,
                       TransactionOperations tx, Clock clock, ExcuseKicker kicker) {
        this.port = port;
        this.access = access;
        this.tx = tx;
        this.clock = clock;
        this.kicker = kicker;
    }

    @Override
    public Recorded record(Fault fault) {
        Instant now = clock.instant();
        if (fault.endedAt().isBefore(fault.startedAt()) || fault.endedAt().isAfter(now.plus(CLOCK_SKEW))
                || Duration.between(fault.startedAt(), fault.endedAt()).compareTo(MAX_PERIOD) > 0
                || fault.attempts().stream().anyMatch(a -> a.at().isBefore(fault.startedAt())
                        || a.at().isAfter(fault.endedAt()))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "오류 구간(시작 ≤ 끝, 24시간 이내, 끝이 지금 이전)과 번호 시각(구간 안)을 확인해 주세요");
        }
        // 처음 받은 오류면 맞은 번호 수, 같은 eventId를 다시 보냈으면 비어 있음.
        // null 대신 Optional로 받는다: tx.execute의 null 결과를 Sonar가 "늘 null 아님"으로 읽어 main 게이트가 막혔다(S2583)
        Optional<Integer> matched = tx.execute(s -> {
            // 같은 방 요청끼리 줄 세워 세고 넣음: 동시에 여러 요청이 와도 상한을 넘지 않게
            port.lockRoomCount(fault.roomId());
            // 중복 확인을 먼저: 상한 근처에서 같은 eventId를 다시 보내도 429가 아니라 DUPLICATE (이미 센 번호를 또 세지 않게)
            return port.insert(fault, now)
                .map(id -> {
                    if (port.attemptsSince(fault.roomId(), now.minus(Duration.ofDays(1))) + fault.attempts().size()
                            > MAX_ATTEMPTS_PER_ROOM_PER_DAY) {
                        throw new BusinessException(ErrorCode.DEVICE_FAULT_TOO_MANY);   // 롤백되어 이벤트도 안 남음
                    }
                    return (int) fault.attempts().stream()
                            .filter(a -> port.insertAttempt(id, fault.roomId(), a, access.openBeforeMinutes()))
                            .count();
                });
        });
        // 무르기는 응답과 따로 바로 돈다. 번호가 맞았을 때만 할 일이 늘면 응답 시간 차이로 번호가 맞았는지
        // 짐작할 수 있어서, 이 요청은 맞든 안 맞든(같은 eventId를 다시 보내도) 저장 + 같은 신호 하나만 한다
        kicker.kick();
        return Objects.requireNonNull(matched).map(n -> new Recorded(false, n)).orElseGet(() -> new Recorded(true, 0));
    }
}
