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
            // 끝난 예약 (자동 퇴실·노쇼 처리는 v0.2 스케줄러가 한다)
            return new Entry(Result.WRONG_CODE, null);
        }
        if (match.status() == ReservationStatus.BOOKED) {
            // false면 그 사이 다른 입력이 먼저 체크인한 것 → 이미 IN_USE라 문만 연다
            statuses.checkInIfBooked(match.id(), now, policy.openBeforeMinutes());
        }
        return new Entry(Result.OPENED, match.id());
    }
}
