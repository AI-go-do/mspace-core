package kr.ac.chosun.mspace.reservation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.reservation.application.ReservationQueryPort.CodeMatch;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.springframework.stereotype.Service;

/**
 * 키패드 번호 확인 (FR-ACC-001). 번호는 시작 10분 전부터 끝나기 전까지만 맞다.
 * 체크인은 조건부 UPDATE라, 같은 번호를 동시에 두 번 눌러도 체크인은 한 번만 된다 (둘 다 문은 열림).
 * 호출하는 쪽(access) 트랜잭션 안에서 부른다.
 */
@Service
public class KeypadEntryService implements KeypadEntryUseCase {

    private final ReservationQueryPort queries;
    private final ReservationStatusPort statuses;
    private final AccessPolicy policy;
    private final Clock clock;

    public KeypadEntryService(ReservationQueryPort queries, ReservationStatusPort statuses, AccessPolicy policy,
                              Clock clock) {
        this.queries = queries;
        this.statuses = statuses;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public Entry enter(int roomId, String code) {
        if (code == null || !code.matches("[0-9]{6}")) {
            return new Entry(Result.WRONG_CODE, null);
        }
        CodeMatch match = queries.findActiveByCode(roomId, code).orElse(null);
        if (match == null) {
            return new Entry(Result.WRONG_CODE, null);
        }
        Instant now = clock.instant();
        Instant opensAt = match.startAt().minus(Duration.ofMinutes(policy.openBeforeMinutes()));
        if (now.isBefore(opensAt)) {
            return new Entry(Result.NOT_YET, match.id());
        }
        if (!now.isBefore(match.endAt())) {
            // 끝난 예약 (상태는 1분 정리가 AUTO_CHECKED_OUT·NO_SHOW로 바꾼다)
            return new Entry(Result.WRONG_CODE, null);
        }
        if (match.status() == ReservationStatus.BOOKED && now.isAfter(match.checkinDeadline())) {
            // 입실 마감(시작 + 15분)이 지났는데 아직 입실 안 함 = 노쇼. 1분 정리가 상태를 바꾸기 전이라도 바로 막는다.
            // 끝난 예약과 같은 WRONG_CODE라 키패드 쪽은 바꿀 게 없다. 이미 입실한(IN_USE) 사람은 끝나기 전까지 계속 열린다
            return new Entry(Result.WRONG_CODE, null);
        }
        if (match.status() == ReservationStatus.BOOKED
                && !statuses.checkInIfBooked(match.id(), now, policy.openBeforeMinutes())
                && !stillInUse(roomId, code, match.id())) {
            // 읽은 뒤 체크인 전에 대표자가 취소함 (시작 10분 전 ~ 시작 사이에는 둘 다 가능). 취소된 번호로는 열지 않는다
            return new Entry(Result.WRONG_CODE, null);
        }
        return new Entry(Result.OPENED, match.id());
    }

    /**
     * 체크인이 0줄을 바꿨을 때 다시 읽는다. findActiveByCode가 예약 줄을 잠가서 보통은 생기지 않지만,
     * 잠금 방식이 바뀌어도 취소·퇴실된 예약으로 문이 열리지 않게 한 번 더 막는다
     */
    private boolean stillInUse(int roomId, String code, long reservationId) {
        return queries.findActiveByCode(roomId, code)
                .filter(m -> m.id() == reservationId && m.status() == ReservationStatus.IN_USE)
                .isPresent();
    }
}
