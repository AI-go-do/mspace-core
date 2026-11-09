package kr.ac.chosun.mspace.device.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.ExcuseNoShowsUseCase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 장치 오류 받기 (Q-19). 오류·번호 저장은 한 트랜잭션, 노쇼 무르기는 커밋 뒤 따로
 * (무르기가 실패해도 오류 기록은 남고, 1분 정리가 다시 무른다).
 */
@Service
class DeviceFaultService implements DeviceFaultUseCase {

    /** 한 번에 받는 오류 구간 상한. 젯슨이 하루 넘게 꺼져 있었으면 나눠 보낸다 */
    static final Duration MAX_PERIOD = Duration.ofHours(24);

    /** 장치 시계가 조금 빨라도 받는다 */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final DeviceFaultPort port;
    private final ExcuseNoShowsUseCase excuses;
    private final AccessPolicy access;
    private final TransactionOperations tx;
    private final Clock clock;

    DeviceFaultService(DeviceFaultPort port, ExcuseNoShowsUseCase excuses, AccessPolicy access,
                       TransactionOperations tx, Clock clock) {
        this.port = port;
        this.excuses = excuses;
        this.access = access;
        this.tx = tx;
        this.clock = clock;
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
        Integer matched = tx.execute(s -> port.insert(fault, now)
                .map(id -> (int) fault.attempts().stream()
                        .filter(a -> port.insertAttempt(id, fault.roomId(), a, access.openBeforeMinutes()))
                        .count())
                .orElse(null));
        if (matched == null) {
            return new Recorded(true, 0, 0);
        }
        return new Recorded(false, matched, matched > 0 ? excuses.excuse(now) : 0);
    }
}
