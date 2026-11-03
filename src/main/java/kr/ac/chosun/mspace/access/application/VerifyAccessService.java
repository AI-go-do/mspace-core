package kr.ac.chosun.mspace.access.application;

import java.time.Clock;
import java.time.Instant;
import kr.ac.chosun.mspace.access.application.KeypadLockPort.LockState;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.KeypadEntryUseCase;
import kr.ac.chosun.mspace.reservation.application.KeypadEntryUseCase.Entry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키패드 입력 처리 (FR-ACC-001, POST /api/access/verify). 순서:
 * <ol>
 *   <li>그 방 잠금 줄을 잡는다 → 같은 방 입력은 한 줄로 처리돼 틀린 횟수가 겹쳐 세지지 않는다</li>
 *   <li>같은 eventId를 이미 처리했으면 처음 결과를 그대로 (장치 재전송이 횟수를 늘리지 않게)</li>
 *   <li>잠겨 있으면 LOCKED (맞는 번호여도)</li>
 *   <li>번호 확인은 예약 기능(KeypadEntryUseCase)에 맡긴다. 처음 맞으면 체크인</li>
 *   <li>WRONG_CODE면 연속 틀린 횟수 +1, 5번이면 1분 잠금. 열리면 0으로. NOT_YET은 진짜 번호라 세지 않는다</li>
 * </ol>
 */
@Service
public class VerifyAccessService {

    private final KeypadLockPort port;
    private final KeypadEntryUseCase entries;
    private final AccessPolicy policy;
    private final Clock clock;

    public VerifyAccessService(KeypadLockPort port, KeypadEntryUseCase entries, AccessPolicy policy, Clock clock) {
        this.port = port;
        this.entries = entries;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public Verdict verify(String eventId, int roomId, String deviceId, String code) {
        LockState lock = port.lockRoom(roomId).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        var earlier = port.findEvent(eventId);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Instant now = clock.instant();
        if (lock.lockedUntil() != null && now.isBefore(lock.lockedUntil())) {
            Verdict locked = Verdict.denied("LOCKED");
            port.saveEvent(eventId, roomId, deviceId, locked, null);
            return locked;
        }

        Entry entry = entries.enter(roomId, code);
        Verdict verdict = switch (entry.result()) {
            case OPENED -> Verdict.OPENED;
            case NOT_YET -> Verdict.denied("NOT_YET");
            case WRONG_CODE -> Verdict.denied("WRONG_CODE");
        };
        switch (entry.result()) {
            case OPENED -> port.saveLock(roomId, 0, null);
            case WRONG_CODE -> {
                int failed = lock.failedCount() + 1;
                if (failed >= policy.lockAfterFailures()) {
                    port.saveLock(roomId, 0, now.plusSeconds(policy.lockSeconds()));
                } else {
                    port.saveLock(roomId, failed, lock.lockedUntil());
                }
            }
            case NOT_YET -> { }
        }
        port.saveEvent(eventId, roomId, deviceId, verdict, entry.reservationId());
        return verdict;
    }
}
