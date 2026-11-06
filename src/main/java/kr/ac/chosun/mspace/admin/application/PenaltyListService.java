package kr.ac.chosun.mspace.admin.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/** 관리자 경고·정지 목록. 경고·정지는 사람이 많아도 하루 수십 줄이라 기간만 걸러 전부 돌려준다 */
@Service
class PenaltyListService implements PenaltyListUseCase {

    private final PenaltyListPort port;
    private final Clock clock;

    PenaltyListService(PenaltyListPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    @Override
    public Penalties list(long adminId, Integer days) {
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        int d = days == null ? DEFAULT_DAYS : days;
        if (d < 1 || d > MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "days는 1–" + MAX_DAYS + " 사이여야 합니다");
        }
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(d));
        return new Penalties(d, port.warnings(since), port.suspensions(since, now));
    }
}
