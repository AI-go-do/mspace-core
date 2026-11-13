package kr.ac.chosun.mspace.access.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 관리자 화면 테스트용 입실 확인 (11/13 지영: "QR 찍어서 입실하는 거 끄고 입실 확인 버튼").
 * 그 예약의 입실 번호로 QR·키패드와 똑같은 {@link VerifyAccessService#verify}를 부른다.
 * 그래서 BOOKED → IN_USE, 입실 기록(access_event), 그 뒤 퇴실·자동 퇴실·노쇼 판단이 QR로 들어간 것과 같다.
 * 기록의 장치 이름만 {@value #DEVICE_ID}라 나중에 QR과 구분된다.
 * <ul>
 *   <li>설정 mspace.access.admin-check-in이 꺼져 있으면 403 ADMIN_CHECK_IN_OFF</li>
 *   <li>관리자가 아니면 403 ADMIN_ONLY, 없는 예약 404</li>
 *   <li>끝났거나 취소된 예약, 입실 마감이 지난 예약, 시작 10분 전보다 이른 예약은 409 RESERVATION_NOT_CHECKABLE.
 *       verify를 부르기 전에 막아서, 문 앞 키패드의 "틀린 횟수"(5번이면 1분 잠금)를 올리지 않는다</li>
 *   <li>이미 입실한 예약이면 문만 다시 연다(QR을 다시 찍은 것과 같음)</li>
 * </ul>
 * 문 앞 LED는 켜지 않는다. 화면에 "문 열림"만 보인다 (11/13 지영 선택)
 */
@Service
public class AdminCheckInService {

    static final String DEVICE_ID = "admin-button";

    private final AdminCheckInPort port;
    private final VerifyAccessService access;
    private final AccessPolicy policy;
    private final Clock clock;

    AdminCheckInService(AdminCheckInPort port, VerifyAccessService access, AccessPolicy policy, Clock clock) {
        this.port = port;
        this.access = access;
        this.policy = policy;
        this.clock = clock;
    }

    /** @return 입실 처리된 예약의 방 번호 */
    public int checkIn(long adminId, long reservationId) {
        if (!policy.adminCheckIn()) {
            throw new BusinessException(ErrorCode.ADMIN_CHECK_IN_OFF);
        }
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        Instant now = clock.instant();
        AdminCheckInPort.Target t = port.findOpen(reservationId, now).orElseThrow(() -> notOpen(reservationId));
        if (!t.checkedIn() && now.isAfter(t.checkinDeadline())) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CHECKABLE, "입실 마감이 지난 예약입니다");
        }
        Verdict v = access.verify(UUID.randomUUID().toString(), t.roomId(), DEVICE_ID, t.accessCode());
        if (!v.opened()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CHECKABLE, switch (v.reason()) {
                case "NOT_YET" -> "시작 " + policy.openBeforeMinutes() + "분 전부터 입실할 수 있습니다";
                case "LOCKED" -> "이 방 문이 잠시 잠겨 있습니다. 1분 뒤 다시 눌러 주세요";
                default -> "지금 입실할 수 있는 예약이 아닙니다";
            });
        }
        return t.roomId();
    }

    private BusinessException notOpen(long reservationId) {
        port.status(reservationId).orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        return new BusinessException(ErrorCode.RESERVATION_NOT_CHECKABLE, "이미 끝났거나 취소된 예약입니다");
    }
}
