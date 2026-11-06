package kr.ac.chosun.mspace.tidy.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.report.application.CameraReportUseCase;
import kr.ac.chosun.mspace.tidy.application.TidyCheckPort.Existing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 정리 확인 한 건 처리 (FR-DEV-105). 한 트랜잭션에서:
 * <ol>
 *   <li>예약 줄을 잠근다. 같은 예약 결과가 동시에 와도 한 줄로 선다</li>
 *   <li>같은 eventId면 처음 결과를 그대로 (DUPLICATE). 다른 eventId로 이미 있으면 409</li>
 *   <li>입실했다 나간 예약(CHECKED_OUT·AUTO_CHECKED_OUT·AWAY_RELEASED)만 받는다</li>
 *   <li>치울 것이 있으면 어질러짐 신고를 자동으로 만든다. 판정은 관리자 (11/6 지영 "자동신고+관리자 판정")</li>
 *   <li>귀중품을 두고 갔으면 대표자·참여자 알림 LEFT_ITEM. 벌칙 없음</li>
 * </ol>
 * 알림은 커밋 뒤에 바깥 채널로 낸다.
 */
@Service
class TidyCheckService implements TidyCheckUseCase {

    static final String LEFT_ITEM_KIND = "LEFT_ITEM";
    private static final Set<String> ENDED = Set.of("CHECKED_OUT", "AUTO_CHECKED_OUT", "AWAY_RELEASED");

    private final TidyCheckPort port;
    private final CameraReportUseCase reports;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;
    private final Clock clock;

    TidyCheckService(TidyCheckPort port, CameraReportUseCase reports, NotificationInbox inbox,
                     TransactionOperations tx, Clock clock) {
        this.port = port;
        this.reports = reports;
        this.inbox = inbox;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public RoomNow current(int roomId) {
        requireRoom(roomId);
        Instant now = clock.instant();
        return new RoomNow(roomId, port.current(roomId, now).orElse(null), port.lastEnded(roomId).orElse(null));
    }

    private record Done(Recorded recorded, List<Notification> notifications) {
    }

    @Override
    public Recorded record(TidyCheck check) {
        requireRoom(check.roomId());
        Done done = tx.execute(status -> {
            String state = port.lockReservation(check.roomId(), check.reservationId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
            Optional<Existing> same = port.findByEvent(check.eventId());
            if (same.isPresent()) {
                // 같은 eventId를 다른 예약에 또 쓰면 장치 쪽 실수. 처음 결과로 덮지 않고 409
                if (same.get().reservationId() != check.reservationId()) {
                    throw new BusinessException(ErrorCode.TIDY_CHECK_EXISTS);
                }
                return new Done(new Recorded(true, same.get().reportId()), List.of());
            }
            if (!ENDED.contains(state)) {
                throw new BusinessException(ErrorCode.RESERVATION_NOT_ENDED);
            }
            if (port.existsForReservation(check.reservationId())) {
                throw new BusinessException(ErrorCode.TIDY_CHECK_EXISTS);
            }
            Instant now = clock.instant();
            Long reportId = check.result() == Result.NEEDS_CLEANING
                    ? reports.fileMessy(check.roomId(), check.reservationId(), content(check.items()))
                    : null;
            port.insert(check, reportId, now);
            return new Done(new Recorded(false, reportId), inbox.store(leftItemAlerts(check), now));
        });
        inbox.publish(done.notifications());
        return done.recorded();
    }

    /** 신고 내용: 카메라가 본 물건 이름 (관리자 판정 근거). 신고 메모 최대 500자 안에서 자른다 */
    static String content(List<String> items) {
        String text = "방 카메라 자동 신고. 본 물건: " + (items.isEmpty() ? "없음" : String.join(", ", items));
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private List<NewNotification> leftItemAlerts(TidyCheck check) {
        if (check.leftItems().isEmpty()) {
            return List.of();
        }
        String what = String.join("·", check.leftItems().stream().distinct().map(TidyCheckService::label).toList());
        String body = port.roomName(check.roomId()) + "에 " + what + "을(를) 두고 가신 것 같아요. 다시 들러 확인해 주세요.";
        List<NewNotification> out = new ArrayList<>();
        for (long user : port.members(check.reservationId())) {
            out.add(new NewNotification(user, check.reservationId(), LEFT_ITEM_KIND, "두고 간 물건이 있어요", body));
        }
        return out;
    }

    private static String label(LeftItem item) {
        return switch (item) {
            case BAG -> "가방";
            case PHONE -> "휴대폰";
            case EARBUDS -> "무선 이어폰";
            case LAPTOP -> "노트북";
        };
    }

    private void requireRoom(int roomId) {
        if (!port.roomExists(roomId)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
    }
}
