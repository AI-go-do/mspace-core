package kr.ac.chosun.mspace.reservation.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.ac.chosun.mspace.common.config.ExtensionPolicy;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.friend.application.FriendCheck;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort.KeyedReservation;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort.NewReservation;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import kr.ac.chosun.mspace.reservation.domain.ReservationTime;
import kr.ac.chosun.mspace.room.application.RoomQuery;
import kr.ac.chosun.mspace.room.application.RoomQuery.ActiveRoom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 미리 예약 만들기 (FR-RSV-001~005).
 * 서버가 먼저 보는 것: 시각 규칙({@link ReservationTime}), 방, 학번, 인원 → 빠르고 친절한 400.
 * DB가 마지막에 막는 것: 겹침, 대표자 1건, 같은 사람 두 방 → 동시에 눌러도 한 건만 성공하는 409.
 * 교착(40P01)은 저장 전 줄 세우기 잠금(어댑터)으로 막고, 그래도 나면 처음부터 새 트랜잭션으로 다시 한다.
 * 멱등 키가 있으면 같은 대표자·같은 키의 예약이 이미 있는지 먼저 보고, 있으면 새로 만들지 않고 그 예약을 돌려준다(#10).
 * 즉시 시작(FR-RSV-103)도 같은 길로 만든다. 다른 점은 시각 규칙({@link ReservationTime#instant})과 is_instant뿐이다.
 * <p>
 * 만들기 전에 지난 예약 정리(노쇼·자동 퇴실)를 한 번 돌린다. 1분 정리를 기다리지 않고, 끝났는데 아직 IN_USE인 내 예약이나
 * 마감이 지났는데 아직 BOOKED인 예약 때문에 409가 나지 않게 하려고 (끝나자마자 이어서 다시 잡을 때, FR-RSV-104).
 * 사용 중에 이어 쓰는 것은 "연장하기"({@link ContinueReservationService}, FR-RSV-105)가 따로 한다.
 */
@Service
public class CreateReservationService implements CreateReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateReservationService.class);

    /** 교착이면 몇 번까지 해 보나 (처음 1번 + 다시 2번). 다 실패하면 503 */
    static final int MAX_ATTEMPTS = 3;

    /** 정지 끝나는 시각 표시: "11/18 14:30" */
    static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("M/d HH:mm");

    private final ReservationCommandPort port;
    private final RoomQuery rooms;
    private final AccessCodeIssuer codes;
    private final ReservationPolicy policy;
    private final Clock clock;
    private final TransactionOperations tx;
    private final SweepReservationsUseCase sweeps;
    private final UsageChainPort chains;
    private final ExtensionPolicy extension;
    private final SuspensionPort suspensions;
    private final FriendCheck friends;
    private final RelayPort relays;
    private final RelayUseCase relay;

    public CreateReservationService(ReservationCommandPort port, RoomQuery rooms, AccessCodeIssuer codes,
                                    ReservationPolicy policy, Clock clock, TransactionOperations tx,
                                    SweepReservationsUseCase sweeps, UsageChainPort chains,
                                    ExtensionPolicy extension, SuspensionPort suspensions,
                                    FriendCheck friends, RelayPort relays, RelayUseCase relay) {
        this.friends = friends;
        this.relays = relays;
        this.relay = relay;
        this.suspensions = suspensions;
        this.sweeps = sweeps;
        this.chains = chains;
        this.extension = extension;
        this.port = port;
        this.rooms = rooms;
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
    @Override
    public Created create(long ownerId, Command command) {
        return make(ownerId, command, false);
    }

    @Override
    public Created startNow(long ownerId, Command command) {
        return make(ownerId, command, true);
    }

    /**
     * 만들기 전에 지난 예약(노쇼·자동 퇴실)을 먼저 정리해서 1분 주기를 기다리지 않게 한다.
     * 정리는 부가 작업이라 DB 오류가 나도 예약 만들기는 계속 간다 (잠금 충돌은 정리 안에서 이미 건너뜀).
     * 정리가 못 돈 탓에 남은 예약이 막으면 409가 나고, 1분 주기 정리가 다시 한다
     */
    private void sweepQuietly() {
        try {
            sweeps.sweep();
        } catch (DataAccessException e) {
            log.warn("예약 만들기 전 정리 실패({}), 그대로 진행", e.getClass().getSimpleName());
        }
    }

    /** 릴레이 다음 사람 찾기. 부가 작업이라 실패해도 예약은 그대로 (1분 정리가 다시 한다) */
    private void advanceQuietly() {
        try {
            relay.advance(clock.instant());
        } catch (RuntimeException e) {
            log.warn("릴레이 넘기기 실패({}), 1분 정리가 다시 함", e.getClass().getSimpleName());
        }
    }

    private Created make(long ownerId, Command command, boolean instant) {
        Optional<Created> earlier = replay(ownerId, command, instant);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        if (instant && command.relayOfferId() != null) {
            // 넘겨받은 자리는 시작 30분 전보다 이른 미리 예약 구간이라 즉시 시작으로 받을 일이 없다
            throw new BusinessException(ErrorCode.RELAY_OFFER_INVALID);
        }
        sweepQuietly();
        for (int attempt = 1; ; attempt++) {
            try {
                Created created = tx.execute(status -> createOnce(ownerId, command, instant));
                if (command.relayOfferId() != null) {
                    advanceQuietly();  // 받으면서 풀린 내 다른 넘김을 다음 사람에게
                }
                return created;
            } catch (PessimisticLockingFailureException e) {
                // 40P01(교착)은 Spring이 이 예외로 바꿔 준다. 트랜잭션은 이미 되돌려졌다
                if (attempt >= MAX_ATTEMPTS) {
                    throw new BusinessException(ErrorCode.RESERVATION_BUSY);
                }
            } catch (BusinessException e) {
                // 뽑은 번호가 같은 방의 다른 예약 번호와 같으면(100만분의 1 정도) 새 번호로 다시 한다
                if (e.errorCode() == ErrorCode.ACCESS_CODE_TAKEN) {
                    if (attempt >= MAX_ATTEMPTS) {
                        throw new BusinessException(ErrorCode.RESERVATION_BUSY);
                    }
                    continue;
                }
                // 같은 키 요청 두 개가 동시에 오면 둘 다 위 replay에서 "없음"을 본다. 늦은 쪽은 저장에서 409(대표자 1건 또는
                // 키 유일 제약)를 받는데, 그 사이 앞 요청이 커밋됐으니 다시 찾으면 처음 예약이 나온다 → 409 대신 그 예약을 200으로
                if (e.errorCode().status() == HttpStatus.CONFLICT) {
                    earlier = replay(ownerId, command, instant);
                    if (earlier.isPresent()) {
                        return earlier.get();
                    }
                }
                throw e;
            }
        }
    }

    /**
     * 같은 대표자·같은 멱등 키로 만든 예약이 있으면 그 예약을 돌려준다 (replayed = true). 키가 없거나 예약이 없으면 빈 값.
     *
     * @throws BusinessException 같은 키인데 방·시각·참여자가 다르면 409(IDEMPOTENCY_KEY_REUSED)
     */
    private Optional<Created> replay(long ownerId, Command command, boolean instant) {
        if (command.idempotencyKey() == null) {
            return Optional.empty();
        }
        return tx.execute(status -> port.findByIdempotencyKey(ownerId, command.idempotencyKey()))
                .map(k -> {
                    if (!sameRequest(k, command, instant)) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    }
                    return new Created(k.id(), k.roomId(), k.startAt().atZone(SEOUL), k.endAt().atZone(SEOUL),
                            k.status(), k.headcount(), k.accessCode(), true);
                });
    }

    /**
     * 방·시작·끝·참여자 학번이 모두 같은지. 참여자는 순서만 무시하고 개수는 그대로 비교한다
     * (Set으로 비교하면 [A, B]와 [A, B, B]가 같아져서, 새로 보냈다면 400일 요청이 200으로 재생된다).
     * 즉시 시작은 시작을 서버가 정하므로(다시 보낸 시각은 다르다) 시작은 비교하지 않는다.
     * 종류(미리 예약 / 지금 바로 쓰기)도 같아야 한다: 미리 예약에 쓴 키를 /instant로 보내면 다른 요청이다
     */
    private static boolean sameRequest(KeyedReservation k, Command c, boolean instant) {
        return k.instant() == instant && k.roomId() == c.roomId() && (instant || k.startAt().equals(c.startAt())) && k.endAt().equals(c.endAt())
                && k.participantStudentIds().stream().sorted().toList()
                        .equals(c.participantStudentIds().stream().sorted().toList());
    }

    /** 한 번 시도: 서버 검사(시각 → 방 → 대표자 → 참여자 → 인원)를 통과하면 DB에 저장한다. 트랜잭션 안에서 불린다 */
    private Created createOnce(long ownerId, Command command, boolean instant) {
        ReservationTime time = instant
                ? ReservationTime.instant(clock.instant(), command.endAt(), policy)
                : ReservationTime.of(command.startAt(), command.endAt(), policy, clock.instant());

        ActiveRoom room = rooms.findActive(command.roomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        String ownerStudentId = port.findVerifiedStudentId(ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        // 이용 정지 (노쇼 FR-RSV-106, 경고 누적 FR-RPT-104): 정지 중이면 대표자로 못 잡는다. 끝나는 날짜를 알려 준다
        Instant now = clock.instant();
        suspensions.suspendedUntil(ownerId, now).ifPresent(until -> {
            throw new BusinessException(ErrorCode.USER_SUSPENDED,
                    "%s까지 이용이 정지되어 예약할 수 없습니다".formatted(UNTIL.format(until.atZone(SEOUL))));
        });

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
        // 모르는 학번을 넣지 못하게, 대표자와 서로 수락한 친구만 (FR-RSV-004, 11/6 지영). 누가 아닌지는 알려 주지 않는다
        if (!friends.canAddAsParticipants(ownerId, ids.values())) {
            throw new BusinessException(ErrorCode.PARTICIPANT_NOT_FRIEND);
        }

        // 연장 제한 우회 막기 (FR-RSV-104·105): 연장까지 쓴 직후 30분 안에 같은 대표자가 새로 잡으면 이어진 사용이라 409
        if (chains.extensionsBefore(ownerId, time.start(), Duration.ofMinutes(extension.chainGapMinutes()))
                >= extension.maxCount()) {
            throw new BusinessException(ErrorCode.RESERVATION_EXTENSION_LIMIT,
                    "연장까지 쓴 뒤 %d분 안에는 새로 예약할 수 없습니다".formatted(extension.chainGapMinutes()));
        }

        // 정지 중인 사람은 참여자로도 못 들어간다 (대표자만 막으면 친구 이름으로 계속 쓸 수 있다)
        if (suspensions.anySuspended(ids.values(), now)) {
            throw new BusinessException(ErrorCode.PARTICIPANT_SUSPENDED);
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

        // 빈방 릴레이로 넘겨받은 자리 (Q-18): 내 것이고, 받을 시간이 남았고, 같은 방, 넘어온 구간 안이어야 한다
        Long offerId = command.relayOfferId();
        if (offerId != null) {
            boolean ok = relays.findOffer(offerId)
                    .filter(o -> o.userId() == ownerId && "OPEN".equals(o.status()) && o.expiresAt().isAfter(now)
                            && o.roomId() == room.id()
                            && !time.start().isBefore(o.startAt()) && !time.end().isAfter(o.endAt()))
                    .isPresent();
            if (!ok) {
                throw new BusinessException(ErrorCode.RELAY_OFFER_INVALID);
            }
        }

        String accessCode = codes.issue();
        long id = port.insert(new NewReservation(room.id(), ownerId, time.start(), time.end(), accessCode,
                participantIds, command.idempotencyKey(), instant, command.createdVia(), false, null, offerId, now));
        // 받은 넘김 표시. 같은 트랜잭션이라 저장이 실패하면 넘김도 그대로 남는다
        // 방 잠금을 기다리는 사이 5분이 지났을 수 있어 지금 시각으로 다시 본다
        if (offerId != null && !relays.take(offerId, ownerId, id, clock.instant())) {
            throw new BusinessException(ErrorCode.RELAY_OFFER_INVALID);
        }
        return new Created(id, room.id(), time.start().atZone(SEOUL), time.end().atZone(SEOUL),
                ReservationStatus.BOOKED, headcount, accessCode, false);
    }
}
