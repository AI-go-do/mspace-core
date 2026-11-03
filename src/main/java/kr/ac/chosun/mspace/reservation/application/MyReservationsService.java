package kr.ac.chosun.mspace.reservation.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.util.Comparator;
import java.util.List;
import kr.ac.chosun.mspace.reservation.application.ReservationQueryPort.Row;
import kr.ac.chosun.mspace.reservation.domain.MaskedName;
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
                            r.endAt().atZone(SEOUL), r.status(), owner, owner ? r.accessCode() : null, people);
                })
                .toList();
    }
}
