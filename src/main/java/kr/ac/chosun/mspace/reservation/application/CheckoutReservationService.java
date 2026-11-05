package kr.ac.chosun.mspace.reservation.application;

import java.time.Clock;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.ReservationQueryPort.Access;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퇴실 (FR-RSV-008). 취소와 같은 방식: "대표자이고 IN_USE일 때만" 조건부 UPDATE 한 번,
 * 바뀐 줄이 없을 때만 이유를 찾아 404 → 403 → 409. 두 번 눌러도 두 번째는 409.
 */
@Service
public class CheckoutReservationService implements CheckoutReservationUseCase {

    private final ReservationStatusPort statuses;
    private final ReservationQueryPort queries;
    private final Clock clock;

    public CheckoutReservationService(ReservationStatusPort statuses, ReservationQueryPort queries, Clock clock) {
        this.statuses = statuses;
        this.queries = queries;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CheckedOut checkout(long reservationId, long userId) {
        if (statuses.checkoutIfInUse(reservationId, userId, clock.instant())) {
            return new CheckedOut(reservationId, ReservationStatus.CHECKED_OUT);
        }
        Access access = queries.findAccess(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!access.owner()) {
            throw new BusinessException(ErrorCode.NOT_RESERVATION_OWNER);
        }
        throw new BusinessException(ErrorCode.RESERVATION_NOT_IN_USE,
                access.status() == ReservationStatus.BOOKED
                        ? "아직 입실하지 않은 예약입니다. 키패드로 입실한 뒤 퇴실할 수 있습니다"
                        : "이미 끝난 예약입니다");
    }
}
