package kr.ac.chosun.mspace.tidy.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.ac.chosun.mspace.common.config.TidyPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.notification.application.Notification;
import kr.ac.chosun.mspace.notification.application.NotificationInbox;
import kr.ac.chosun.mspace.notification.application.NotificationInbox.NewNotification;
import kr.ac.chosun.mspace.report.application.CameraReportUseCase;
import kr.ac.chosun.mspace.report.application.CameraReportUseCase.Warned;
import kr.ac.chosun.mspace.tidy.application.TidyCheckPort.Existing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 정리 확인 한 건 처리 (FR-DEV-105). 한 트랜잭션에서:
 * <ol>
 *   <li>예약 줄을 잠근다. 같은 예약 결과가 동시에 와도 한 줄로 선다</li>
 *   <li>같은 eventId면 처음 결과를 그대로 (DUPLICATE). 다른 eventId로 같은 단계가 이미 있으면 409</li>
 *   <li>입실했다 나간 예약(CHECKED_OUT·AUTO_CHECKED_OUT·AWAY_RELEASED) 중 이 방의 마지막 것만 받는다</li>
 *   <li>FIRST가 LEFT_ITEMS면 대표자·참여자에게 LEFT_ITEM 알림. 신고는 만들지 않는다
 *       (11/10: 쓰레기도 "두고 감"으로 합쳐, 바로 신고 + 2시간 뒤 경고로 두 번 걸리지 않게)</li>
 *   <li>RECHECK는 FIRST가 LEFT_ITEMS이고, 퇴실 + mspace.tidy.recheck-after-minutes가 지났고,
 *       그 사이 다음 예약이 입실하지 않았을 때만 받는다</li>
 *   <li>RECHECK에 확신도 mspace.tidy.warn-min-confidence 이상인 물건이 남아 있으면 카메라 신고를 만들어
 *       사람 없이 사실로 정하고 그 예약 대표자에게 경고 (11/10 지영 "core가 바로 경고")</li>
 * </ol>
 * 알림은 커밋 뒤에 바깥 채널로 낸다.
 */
@Service
class TidyCheckService implements TidyCheckUseCase {

    static final String LEFT_ITEM_KIND = "LEFT_ITEM";
    static final int NOTIFICATION_BODY_MAX = 300;
    private static final Set<String> ENDED = Set.of("CHECKED_OUT", "AUTO_CHECKED_OUT", "AWAY_RELEASED");

    private final TidyCheckPort port;
    private final CameraReportUseCase reports;
    private final NotificationInbox inbox;
    private final TransactionOperations tx;
    private final Clock clock;
    private final Duration recheckAfter;
    /** 장치 확신도가 float라 기준도 float로 맞춘다. double로 비교하면 0.9f(0.8999…)가 0.9 기준에서 빠진다 */
    private final float warnMinConfidence;

    TidyCheckService(TidyCheckPort port, CameraReportUseCase reports, NotificationInbox inbox,
                     TransactionOperations tx, Clock clock, TidyPolicy policy) {
        this.port = port;
        this.reports = reports;
        this.inbox = inbox;
        this.tx = tx;
        this.clock = clock;
        this.recheckAfter = Duration.ofMinutes(policy.recheckAfterMinutes());
        this.warnMinConfidence = (float) policy.warnMinConfidence();
    }

    @Override
    public RoomNow current(int roomId) {
        requireRoom(roomId);
        Instant now = clock.instant();
        CurrentReservation current = port.current(roomId, now).orElse(null);
        LastEnded last = port.lastEnded(roomId).orElse(null);
        return new RoomNow(roomId, current, last, recheck(roomId, last));
    }

    /** 마지막 퇴실 예약을 다시 볼 차례인지. 그 뒤 누가 입실했으면 다음 팀 물건과 섞이니 주지 않는다 */
    private Recheck recheck(int roomId, LastEnded last) {
        if (last == null || port.enteredSince(roomId, last.reservationId(), last.endedAt())
                || !needsRecheck(last.reservationId())) {
            return null;
        }
        return new Recheck(last.reservationId(), last.endedAt().plus(recheckAfter));
    }

    private boolean needsRecheck(long reservationId) {
        return port.result(reservationId, Phase.FIRST).filter(r -> r == Result.LEFT_ITEMS).isPresent()
                && port.result(reservationId, Phase.RECHECK).isEmpty();
    }

    private record Done(Recorded recorded, List<Notification> notifications) {
    }

    @Override
    public Recorded submit(TidyCheck check) {
        requireRoom(check.roomId());
        Done done = tx.execute(status -> {
            String state = port.lockReservation(check.roomId(), check.reservationId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
            Optional<Existing> same = port.findByEvent(check.eventId());
            if (same.isPresent()) {
                // 같은 eventId를 다른 예약·단계에 또 쓰면 장치 쪽 실수. 처음 결과로 덮지 않고 409
                if (same.get().reservationId() != check.reservationId() || same.get().phase() != check.phase()) {
                    throw new BusinessException(ErrorCode.TIDY_CHECK_EXISTS);
                }
                return new Done(new Recorded(true, same.get().reportId()), List.of());
            }
            if (!ENDED.contains(state)) {
                throw new BusinessException(ErrorCode.RESERVATION_NOT_ENDED);
            }
            // 장치 토큰은 방마다 공용이라, 지난 예약에 결과를 붙이지 못하게 이 방의 마지막 퇴실 예약만 받는다
            Optional<LastEnded> last = port.lastEnded(check.roomId())
                    .filter(e -> e.reservationId() == check.reservationId());
            if (last.isEmpty()) {
                throw new BusinessException(ErrorCode.TIDY_CHECK_NOT_LATEST);
            }
            if (port.result(check.reservationId(), check.phase()).isPresent()) {
                throw new BusinessException(ErrorCode.TIDY_CHECK_EXISTS);
            }
            Instant now = clock.instant();
            if (check.phase() == Phase.RECHECK) {
                requireRecheckDue(check, last.get(), now);
            }
            if (check.phase() == Phase.FIRST) {
                port.insert(check, null, now);
                return new Done(new Recorded(false, null), inbox.store(leftItemAlerts(check), now));
            }
            List<Item> sure = check.items().stream().filter(i -> i.confidence() >= warnMinConfidence).toList();
            if (sure.isEmpty()) {
                port.insert(check, null, now);
                return new Done(new Recorded(false, null), List.of());
            }
            Warned warned = reports.warnLeftItems(check.roomId(), check.reservationId(), content(sure),
                    "두고 간 물건을 " + within() + " 안에 챙겨 가지 않았어요.", now);
            port.insert(check, warned.reportId(), now);
            return new Done(new Recorded(false, warned.reportId()), inbox.store(warned.notifications(), now));
        });
        inbox.publish(done.notifications());
        return done.recorded();
    }

    private void requireRecheckDue(TidyCheck check, LastEnded last, Instant now) {
        // "지금 사용 중"이 아니라 "퇴실 뒤 누가 입실했나"로 본다. 한 번이라도 들어왔으면(이미 나갔어도) 섞였다
        if (port.enteredSince(check.roomId(), check.reservationId(), last.endedAt())
                || !needsRecheck(check.reservationId())) {
            throw new BusinessException(ErrorCode.TIDY_RECHECK_NOT_NEEDED);
        }
        Instant due = last.endedAt().plus(recheckAfter);
        // 기한 전에 본 화면을 늦게 보내도 받지 않는다 (그 사이 가져갔을 수 있다)
        if (now.isBefore(due) || check.observedAt().isBefore(due)) {
            throw new BusinessException(ErrorCode.TIDY_RECHECK_TOO_EARLY);
        }
    }

    private List<NewNotification> leftItemAlerts(TidyCheck check) {
        if (check.result() != Result.LEFT_ITEMS) {
            return List.of();
        }
        String body = leftItemBody(port.roomName(check.roomId()),
                check.items().stream().map(Item::name).distinct().toList(), within());
        List<NewNotification> out = new ArrayList<>();
        for (long user : port.members(check.reservationId())) {
            out.add(new NewNotification(user, check.reservationId(), LEFT_ITEM_KIND, "두고 간 물건이 있어요", body));
        }
        return out;
    }

    /** 알림 본문은 300자(V10 notification.body). 물건이 많으면 앞에서부터 넣고 "외 N개"로 줄인다. 기한 문장은 늘 남긴다 */
    static String leftItemBody(String room, List<String> names, String within) {
        String tail = "을(를) 두고 가신 것 같아요. " + within + " 안에 챙겨 가 주세요.";
        for (int shown = names.size(); shown >= 1; shown--) {
            String what = String.join("·", names.subList(0, shown))
                    + (shown < names.size() ? " 외 " + (names.size() - shown) + "개" : "");
            String body = room + "에 " + what + tail;
            if (body.length() <= NOTIFICATION_BODY_MAX) {
                return body;
            }
        }
        return room + "에 물건 " + names.size() + "개" + tail;
    }

    /** 2시간처럼 사람이 읽는 기한 */
    private String within() {
        long minutes = recheckAfter.toMinutes();
        return minutes % 60 == 0 ? (minutes / 60) + "시간" : minutes + "분";
    }

    /** 신고 내용: 다시 볼 때 남아 있던 물건과 확신도. 신고 메모 최대 500자 안에서 자른다 */
    static String content(List<Item> items) {
        String text = "방 카메라 자동 확인: 퇴실 뒤 다시 봐도 남은 물건 " + String.join(", ",
                items.stream().map(i -> "%s(%.2f)".formatted(i.name(), i.confidence())).toList());
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private void requireRoom(int roomId) {
        if (!port.roomExists(roomId)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
    }

    @Override
    public List<Listed> list(long adminId, Integer days) {
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        int d = days == null ? DEFAULT_DAYS : days;
        if (d < 1 || d > MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "days는 1–" + MAX_DAYS + " 사이여야 합니다");
        }
        return port.receivedSince(clock.instant().minus(Duration.ofDays(d)));
    }
}
