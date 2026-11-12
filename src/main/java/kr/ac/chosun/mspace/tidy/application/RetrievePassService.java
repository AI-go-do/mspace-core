package kr.ac.chosun.mspace.tidy.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import kr.ac.chosun.mspace.common.config.TidyPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.AccessCodeIssuer;
import kr.ac.chosun.mspace.tidy.application.RetrievePassPort.Ended;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "찾으러 갈게요" 10분 QR. 번호는 예약 입실 번호와 같은 6자리라 키패드로 눌러도 열린다.
 * 같은 방에서 지금 쓰이는 번호(끝나지 않은 예약, 살아 있는 QR)와 겹치지 않게 뽑는다.
 * 남는 틈: QR을 준 뒤 10분 안에 같은 방 새 예약이 같은 번호를 받으면 문 앞에서는 예약이 먼저다 (확률 100만분의 1 수준)
 */
@Service
class RetrievePassService implements RetrievePassUseCase {

    private static final Set<String> ENDED = Set.of("CHECKED_OUT", "AUTO_CHECKED_OUT", "AWAY_RELEASED");
    private static final int MAX_TRIES = 20;

    private final RetrievePassPort port;
    private final AccessCodeIssuer codes;
    private final Clock clock;
    private final Duration within;
    private final Duration alive;

    RetrievePassService(RetrievePassPort port, AccessCodeIssuer codes, Clock clock, TidyPolicy policy) {
        this.port = port;
        this.codes = codes;
        this.clock = clock;
        this.within = Duration.ofMinutes(policy.recheckAfterMinutes());
        this.alive = Duration.ofMinutes(policy.retrievePassMinutes());
    }

    @Override
    @Transactional
    public Pass issue(long reservationId, long userId) {
        Ended r = port.lockMine(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!ENDED.contains(r.status()) || r.checkedOutAt() == null) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ENDED);
        }
        if (!port.leftItems(reservationId)) {
            throw new BusinessException(ErrorCode.RETRIEVE_NOT_NEEDED);
        }
        Instant now = clock.instant();
        if (!now.isBefore(r.checkedOutAt().plus(within))) {
            throw new BusinessException(ErrorCode.RETRIEVE_TOO_LATE);
        }
        // 살아 있는 QR은 문을 열었어도 그대로 준다 (10분 안에는 여러 번 열 수 있음, 결정 30).
        // 닫힌 뒤 새 QR은 한 번도 문을 안 열었을 때만 (11/12 지영 "문 한 번 열면 못 받게")
        Optional<Pass> same = port.alive(reservationId, now);
        if (same.isPresent()) {
            return same.get();
        }
        if (port.used(reservationId)) {
            throw new BusinessException(ErrorCode.RETRIEVE_ALREADY_USED);
        }
        Pass pass = new Pass(reservationId, r.roomId(), freeCode(r.roomId(), now), now.plus(alive));
        port.insert(pass, userId, now);
        return pass;
    }

    private String freeCode(int roomId, Instant now) {
        for (int i = 0; i < MAX_TRIES; i++) {
            String code = codes.issue();
            if (!port.codeTaken(roomId, code, now)) {
                return code;
            }
        }
        throw new BusinessException(ErrorCode.ACCESS_CODE_TAKEN);
    }

    @Override
    @Transactional
    public Optional<Long> open(int roomId, String code) {
        if (code == null || !code.matches("[0-9]{6}")) {
            return Optional.empty();
        }
        Optional<RetrievePassPort.Live> live = port.find(roomId, code, clock.instant());
        if (live.isEmpty()) {
            return Optional.empty();
        }
        // 자동 판정·다시 확인과 같은 예약 잠금을 잡고, 기다리는 동안 QR이 닫혔는지 지금 시각으로 다시 본다
        if (!port.lockReservation(live.get().reservationId())) {
            return Optional.empty();
        }
        return port.markUsed(live.get().passId(), clock.instant())
                ? Optional.of(live.get().reservationId()) : Optional.empty();
    }
}
