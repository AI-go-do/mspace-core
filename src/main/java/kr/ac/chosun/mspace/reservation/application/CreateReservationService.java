package kr.ac.chosun.mspace.reservation.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort.NewReservation;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort.RoomCapacity;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 미리 예약 만들기 (FR-RSV-001~005).
 * 서버가 먼저 보는 것: 시각 형식(30분·2시간·지난 시각·7일), 방, 학번, 인원 → 빠르고 친절한 400.
 * DB가 마지막에 막는 것: 겹침, 대표자 1건, 같은 사람 두 방 → 동시에 눌러도 한 건만 성공하는 409.
 * 교착(40P01)은 저장 전 줄 세우기 잠금(어댑터)으로 막고, 그래도 나면 처음부터 새 트랜잭션으로 다시 한다.
 */
@Service
public class CreateReservationService {

    /** 교착이면 몇 번까지 해 보나 (처음 1번 + 다시 2번). 다 실패하면 503 */
    static final int MAX_ATTEMPTS = 3;

    private final ReservationCommandPort port;
    private final AccessCodeGenerator codes;
    private final ReservationPolicy policy;
    private final Clock clock;
    private final TransactionOperations tx;

    public CreateReservationService(ReservationCommandPort port, AccessCodeGenerator codes,
                                    ReservationPolicy policy, Clock clock, TransactionOperations tx) {
        this.port = port;
        this.codes = codes;
        this.policy = policy;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * 미리 예약을 만든다. 한 번 시도가 트랜잭션 하나이고, 교착으로 실패하면 최대 {@value #MAX_ATTEMPTS}번까지 처음부터 다시 한다.
     * <p>
     * 왜 처음부터인가: 교착이 나면 PostgreSQL이 그 트랜잭션을 통째로 무효로 만들어서, 그 안에서는 SQL을 더 할 수 없다.
     * 다시 하면 보통 이긴 쪽 예약이 이미 저장돼 있어 409로 끝난다 (학생은 500 대신 "이미 예약된 시간"을 받는다).
     *
     * @param ownerId 대표자 회원 id (로그인 사용자)
     * @param command 방·시각·참여자 학번 (대표자 학번은 빼고)
     * @return 만든 예약과 키패드 번호
     * @throws BusinessException 규칙 위반이면 400·404, DB 제약(겹침 등)에 걸리면 409, 교착이 계속되면 503
     */
    public Created create(long ownerId, Command command) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> createOnce(ownerId, command));
            } catch (PessimisticLockingFailureException e) {
                // 40P01(교착)은 Spring이 이 예외로 바꿔 준다. 트랜잭션은 이미 되돌려졌다
                if (attempt >= MAX_ATTEMPTS) {
                    throw new BusinessException(ErrorCode.RESERVATION_BUSY);
                }
            }
        }
    }

    /** 한 번 시도: 서버 검사(시각 → 방 → 대표자 → 참여자 → 인원)를 통과하면 DB에 저장한다. 트랜잭션 안에서 불린다 */
    private Created createOnce(long ownerId, Command command) {
        Instant start = command.startAt();
        Instant end = command.endAt();
        checkTimes(start, end);

        RoomCapacity room = port.findActiveRoom(command.roomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        String ownerStudentId = port.findVerifiedStudentId(ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        // 대표자는 서버가 넣는다. 목록에 대표자 학번이 또 있으면 같은 사람 두 번 → 400
        List<String> others = command.participantStudentIds();
        Set<String> seen = new HashSet<>();
        seen.add(ownerStudentId);
        for (String studentId : others) {
            if (!seen.add(studentId)) {
                throw new BusinessException(ErrorCode.PARTICIPANT_DUPLICATE);
            }
        }

        Map<String, Long> ids = port.findVerifiedUserIds(others);
        if (ids.size() != others.size()) {
            throw new BusinessException(ErrorCode.PARTICIPANT_NOT_FOUND);
        }

        int headcount = others.size() + 1;
        if (headcount < room.minPeople() || headcount > room.maxPeople()) {
            throw new BusinessException(ErrorCode.HEADCOUNT_OUT_OF_RANGE,
                    "%s은 %d명 이상 %d명 이하입니다 (입력: %d명)"
                            .formatted(room.name(), room.minPeople(), room.maxPeople(), headcount));
        }

        List<Long> participantIds = new ArrayList<>();
        participantIds.add(ownerId);
        others.forEach(s -> participantIds.add(ids.get(s)));

        String accessCode = codes.next();
        long id = port.insert(new NewReservation(room.id(), ownerId, start, end, accessCode, participantIds));
        return new Created(id, room.id(), start.atZone(SEOUL), end.atZone(SEOUL), "BOOKED", headcount, accessCode);
    }

    /**
     * 시각 규칙: 30분 칸에 맞는지, 길이가 0보다 크고 최대(2시간) 이하인지, 지난 시각이 아닌지, 시작이 7일 안인지.
     * 숫자는 {@link ReservationPolicy} 설정값을 쓴다.
     */
    private void checkTimes(Instant start, Instant end) {
        long slotSeconds = policy.slotMinutes() * 60L;
        if (!onSlot(start, slotSeconds) || !onSlot(end, slotSeconds)) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ON_SLOT);
        }
        Duration length = Duration.between(start, end);
        if (length.isNegative() || length.isZero() || length.toMinutes() > policy.maxMinutes()) {
            throw new BusinessException(ErrorCode.RESERVATION_INVALID_LENGTH);
        }
        Instant now = clock.instant();
        if (!start.isAfter(now)) {
            throw new BusinessException(ErrorCode.RESERVATION_IN_PAST);
        }
        // 시작 시각만 본다: 7일째 23:00–다음 날 01:00은 된다 (자정 넘김 허용)
        LocalDate today = LocalDate.ofInstant(now, SEOUL);
        if (!LocalDate.ofInstant(start, SEOUL).isBefore(today.plusDays(policy.bookingWindowDays()))) {
            throw new BusinessException(ErrorCode.RESERVATION_OUT_OF_RANGE);
        }
    }

    /** 서울은 UTC+9라 UTC로 30분 단위면 서울로도 30분 단위 */
    private static boolean onSlot(Instant t, long slotSeconds) {
        return t.getNano() == 0 && t.getEpochSecond() % slotSeconds == 0;
    }

    /** 예약 만들기 입력. 시각은 Instant(UTC 기준 한 순간)로 받는다 */
    public record Command(int roomId, Instant startAt, Instant endAt, List<String> participantStudentIds) {
    }

    /** 예약 만들기 결과. 시각은 서울 시간대로 돌려준다 */
    public record Created(long id, int roomId, ZonedDateTime startAt, ZonedDateTime endAt,
                          String status, int headcount, String accessCode) {
    }
}
