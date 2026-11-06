package kr.ac.chosun.mspace.reservation.application;

import kr.ac.chosun.mspace.common.config.InstantCancelPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.ReservationQueryPort.Access;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시작 전 취소 (FR-RSV-007). 즉시 시작은 입실 전이면 누른 뒤 {@link InstantCancelPolicy#minutes()}분 안까지 취소할 수 있다 (11/6 팀 결정, docs #57).
 * <p>
 * 먼저 조회하고 바꾸는 대신, "대표자이고 BOOKED이고 시작 전일 때만" 바꾸는 조건부 UPDATE 한 번으로 한다.
 * 조회와 변경 사이에 체크인·다른 취소가 끼어들 틈이 없어서, 두 번 눌러도 두 번째는 409로 끝난다
 * (비서는 이걸 믿고 취소에 멱등 키를 안 보낸다).
 * 바뀐 줄이 없을 때만 이유를 찾아 404(없거나 내 예약 아님) → 403(참여자) → 409(이미 시작·끝남) 중 하나로 답한다.
 */
@Service
public class CancelReservationService implements CancelReservationUseCase {

    private final ReservationStatusPort commands;
    private final ReservationQueryPort queries;
    private final InstantCancelPolicy instantCancel;

    public CancelReservationService(ReservationStatusPort commands, ReservationQueryPort queries,
                                    InstantCancelPolicy instantCancel) {
        this.commands = commands;
        this.queries = queries;
        this.instantCancel = instantCancel;
    }

    @Override
    @Transactional
    public Canceled cancel(long reservationId, long userId) {
        if (commands.cancelIfBookedBeforeStart(reservationId, userId, instantCancel.minutes())) {
            return new Canceled(reservationId, ReservationStatus.CANCELED);
        }
        Access access = queries.findAccess(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!access.owner()) {
            throw new BusinessException(ErrorCode.NOT_RESERVATION_OWNER);
        }
        throw new BusinessException(ErrorCode.RESERVATION_NOT_CANCELABLE, reason(access.status()));
    }

    private static String reason(ReservationStatus status) {
        return switch (status) {
            case BOOKED -> "이미 시작 시각이 지난 예약은 취소할 수 없습니다";
            case IN_USE -> "사용 중인 예약은 취소할 수 없습니다. 퇴실해 주세요";
            case CANCELED -> "이미 취소된 예약입니다";
            default -> "이미 끝난 예약입니다";
        };
    }
}
