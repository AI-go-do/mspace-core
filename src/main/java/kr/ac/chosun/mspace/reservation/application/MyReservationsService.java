package kr.ac.chosun.mspace.reservation.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.util.Comparator;
import java.util.List;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import kr.ac.chosun.mspace.reservation.application.ReservationQueryPort.Row;
import kr.ac.chosun.mspace.common.privacy.MaskedName;
import org.springframework.stereotype.Service;

/** 내 예약 보기 (FR-RSV-006). 번호는 대표자에게만, 참여자 이름은 가려서 */
@Service
public class MyReservationsService implements MyReservationsUseCase {

    /** 끝나지 않은 예약이 위(시작 빠른 순), 끝난 예약은 아래(최근 것부터) */
    static final Comparator<Row> ORDER = Comparator
            .comparing((Row r) -> !r.status().isActive())
            .thenComparing((a, b) -> a.status().isActive()
                    ? a.startAt().compareTo(b.startAt())
                    : b.startAt().compareTo(a.startAt()));

    private final ReservationQueryPort port;

    public MyReservationsService(ReservationQueryPort port) {
        this.port = port;
    }

    @Override
    public List<MyReservation> findMine(long userId) {
        return port.findByMember(userId).stream()
                .sorted(ORDER)
                .map(r -> {
                    boolean owner = r.ownerId() == userId;
                    List<Participant> people = r.participants().stream()
                            .map(p -> new Participant(MaskedName.of(p.name()), p.owner()))
                            .toList();
                    return new MyReservation(r.id(), r.roomId(), r.roomName(), r.startAt().atZone(SEOUL),
                            r.endAt().atZone(SEOUL), r.status(), owner, codeFor(owner, r.status(), r.accessCode()), people);
                })
                .toList();
    }

    /**
     * 키패드 번호는 대표자에게만, 그리고 끝나지 않은 예약(BOOKED·IN_USE)일 때만 보여 준다.
     * 노쇼·취소·퇴실된 예약의 번호는 키패드에서 더 이상 안 열리므로(WRONG_CODE) 화면에도 내보내지 않는다
     */
    private static String codeFor(boolean owner, ReservationStatus status, String code) {
        return owner && status.isActive() ? code : null;
    }
}
