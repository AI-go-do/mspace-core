package kr.ac.chosun.mspace.reservation.application;

import java.time.Clock;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.ReservationQueryPort.Access;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "아직 있어요" (FR-DEV-103, FR-NTF-103). 센서 오판 대비. 11/5 지영: 실제 서비스에도 넣는다(설정으로 끌 수 있음).
 * 11/5 지영 "docs대로": 예약당 1번. 누르면 자리 비움 10분 알림·15분 퇴실을 누른 시각부터 다시 잰다.
 * 퇴실과 같은 방식: 조건부 UPDATE 한 번, 안 바뀌었을 때만 이유를 찾아 404 → 403 → 409.
 */
@Service
public class StillHereService implements StillHereUseCase {

    private final StillHerePort port;
    private final ReservationQueryPort queries;
    private final SensorPolicy sensor;
    private final Clock clock;

    public StillHereService(StillHerePort stillHere, ReservationQueryPort queries, SensorPolicy sensor,
                            Clock clock) {
        this.port = stillHere;
        this.queries = queries;
        this.sensor = sensor;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void stillHere(long reservationId, long userId) {
        if (!sensor.away().stillHereEnabled()) {
            throw new BusinessException(ErrorCode.STILL_HERE_DISABLED);
        }
        if (port.markIfInUse(reservationId, userId, clock.instant())) {
            return;
        }
        Access access = queries.findAccess(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!access.owner()) {
            throw new BusinessException(ErrorCode.NOT_RESERVATION_OWNER);
        }
        if (access.status() != ReservationStatus.IN_USE) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_IN_USE, "사용 중인 예약에서만 쓸 수 있습니다");
        }
        // 대표자이고 사용 중인데 안 바뀜 = 이미 썼다
        throw new BusinessException(ErrorCode.STILL_HERE_ALREADY_USED);
    }
}
