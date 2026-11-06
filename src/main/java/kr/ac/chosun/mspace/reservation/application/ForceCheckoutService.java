package kr.ac.chosun.mspace.reservation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.reservation.application.CheckoutReservationUseCase.CheckedOut;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관리자 강제 퇴실 (FR-RPT-104 퇴출). 보통 퇴실과 같은 조건부 UPDATE라 두 관리자가 동시에 눌러도 한 번만 된다.
 * <ul>
 *   <li>관리자가 아니면 403 ADMIN_ONLY, 없는 예약 404, 사용 중이 아니면 409 RESERVATION_NOT_IN_USE</li>
 *   <li>사유는 200자까지(넘으면 400). 알림에 그대로 보이므로 이름·학번은 넣지 말라고 화면에 안내한다</li>
 *   <li>퇴실·알림함 저장은 한 트랜잭션. 메일 등 바깥 채널은 커밋 뒤</li>
 * </ul>
 * 경고·정지는 따로 걸지 않는다 (벌칙은 신고 판정이 맡는다).
 */
@Service
class ForceCheckoutService implements ForceCheckoutUseCase {

    static final String KIND = "FORCED_CHECKOUT";

    private final ForceCheckoutPort port;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;
    private final Clock clock;

    ForceCheckoutService(ForceCheckoutPort port, NotificationInbox inbox, TransactionOperations tx, Clock clock) {
        this.port = port;
        this.inbox = inbox;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public CheckedOut forceCheckout(long adminId, long reservationId, String reason) {
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (why != null && why.length() > MAX_REASON) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "사유는 " + MAX_REASON + "자까지 쓸 수 있습니다");
        }
        Instant now = clock.instant();
        List<Notification> stored = tx.execute(s -> {
            String room = port.forceCheckoutIfInUse(reservationId, adminId, why, now)
                    .orElseThrow(() -> notInUse(reservationId));
            String body = room + " 예약을 관리자가 퇴실 처리했어요."
                    + (why == null ? "" : " 사유: " + why);
            List<NewNotification> items = port.members(reservationId).stream()
                    .map(u -> new NewNotification(u, reservationId, KIND, "관리자가 퇴실 처리했어요", body))
                    .toList();
            return inbox.store(items, now);
        });
        inbox.publish(stored);
        return new CheckedOut(reservationId, ReservationStatus.CHECKED_OUT);
    }

    private BusinessException notInUse(long reservationId) {
        ReservationStatus status = port.status(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        return new BusinessException(ErrorCode.RESERVATION_NOT_IN_USE,
                status == ReservationStatus.BOOKED ? "아직 입실하지 않은 예약입니다" : "이미 끝난 예약입니다");
    }
}
