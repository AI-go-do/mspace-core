package kr.ac.chosun.mspace.reservation.application;

import static kr.ac.chosun.mspace.common.config.TimeConfig.SEOUL;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.common.config.ExtensionPolicy;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Created;
import kr.ac.chosun.mspace.reservation.application.ContinuationPort.ContinueTarget;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort.KeyedReservation;
import kr.ac.chosun.mspace.reservation.application.ReservationCommandPort.NewReservation;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 연장하기 (FR-RSV-105, 요구사항 3.2 비교표 C안).
 * 한 트랜잭션: 지금 예약 퇴실(조건부 UPDATE) → 지금부터 endAt까지 새 예약 INSERT(IN_USE, continued_from_id).
 * <p>
 * 순서가 중요하다: 겹침·대표자 1건 제약은 문장마다 바로 검사되므로, 퇴실을 먼저 해야 새 예약이 지금 예약과 겹치지 않는다.
 * 다음 칸을 남이 먼저 잡았으면 INSERT가 409(RESERVATION_TIME_TAKEN)이고 퇴실도 롤백돼 지금 예약은 그대로 남는다.
 * <p>
 * 새 예약은 is_instant = true로 넣는다. 시작이 30분 칸이 아니고(누른 시각) 길이가 2시간을 조금 넘을 수 있어서
 * (13:52 → 16:00), DB valid_length의 즉시 시작 규칙(2시간 30분 미만)을 따른다. 처음부터 IN_USE라 노쇼 마감은 쓰이지 않는다.
 * <p>
 * 1회 제한: 지금 예약이 연장으로 만든 예약이거나, 이어진 사용(같은 대표자가 30분 안에 다시 잡은 예약들)에 이미 연장이 있으면 409.
 * 같은 예약을 동시에 두 번 연장하면 조건부 UPDATE가 한 번만 통과하고, 마지막으로 one_continuation_per_reservation이 막는다.
 */
@Service
public class ContinueReservationService implements ContinueReservationUseCase {

    private final ReservationCommandPort port;
    private final ContinuationPort continuations;
    private final UsageChainPort chains;
    private final ReservationStatusPort statuses;
    private final AccessCodeIssuer codes;
    private final ExtensionPolicy policy;
    private final ReservationPolicy slots;
    private final Clock clock;
    private final TransactionOperations tx;

    public ContinueReservationService(ReservationCommandPort port, ContinuationPort continuations,
                                      UsageChainPort chains, ReservationStatusPort statuses,
                                      AccessCodeIssuer codes, ExtensionPolicy policy, ReservationPolicy slots,
                                      Clock clock, TransactionOperations tx) {
        this.port = port;
        this.continuations = continuations;
        this.chains = chains;
        this.statuses = statuses;
        this.codes = codes;
        this.policy = policy;
        this.slots = slots;
        this.clock = clock;
        this.tx = tx;
    }

    @Override
    public Created continueUse(long reservationId, long userId, Command command) {
        Optional<Created> earlier = replay(reservationId, userId, command);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> continueOnce(reservationId, userId, command));
            } catch (PessimisticLockingFailureException e) {
                // 교착·잠금 대기 초과. 예약 만들기와 같이 처음부터 다시, 3번 다 실패하면 503
                if (attempt >= CreateReservationService.MAX_ATTEMPTS) {
                    throw new BusinessException(ErrorCode.RESERVATION_BUSY);
                }
            } catch (BusinessException e) {
                if (e.errorCode() == ErrorCode.ACCESS_CODE_TAKEN) {
                    if (attempt >= CreateReservationService.MAX_ATTEMPTS) {
                        throw new BusinessException(ErrorCode.RESERVATION_BUSY);
                    }
                    continue;
                }
                // 같은 키로 동시에 두 번 오면 늦은 쪽은 퇴실 UPDATE가 0줄이라 409. 앞 요청이 커밋됐으면 그 결과를 200으로
                if (e.errorCode().status() == HttpStatus.CONFLICT) {
                    earlier = replay(reservationId, userId, command);
                    if (earlier.isPresent()) {
                        return earlier.get();
                    }
                }
                throw e;
            }
        }
    }

    /** 같은 대표자·같은 키로 이 예약을 연장한 적이 있으면 그 결과. 키를 다른 예약이나 다른 끝 시각에 썼으면 409 */
    private Optional<Created> replay(long reservationId, long userId, Command command) {
        if (command.idempotencyKey() == null) {
            return Optional.empty();
        }
        return tx.execute(status -> port.findByIdempotencyKey(userId, command.idempotencyKey())
                .map(k -> {
                    boolean same = continuations.continuedFrom(k.id()).filter(from -> from == reservationId).isPresent()
                            && k.endAt().equals(command.endAt());
                    if (!same) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    }
                    return created(k);
                }));
    }

    private static Created created(KeyedReservation k) {
        return new Created(k.id(), k.roomId(), k.startAt().atZone(SEOUL), k.endAt().atZone(SEOUL), k.status(),
                k.headcount(), k.accessCode(), true);
    }

    /** 한 번 시도. 트랜잭션 안에서 불린다 */
    private Created continueOnce(long reservationId, long userId, Command command) {
        ContinueTarget r = continuations.findContinueTarget(reservationId)
                .filter(t -> t.participantIds().contains(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (r.ownerId() != userId) {
            throw new BusinessException(ErrorCode.NOT_RESERVATION_OWNER);
        }
        if (r.status() != ReservationStatus.IN_USE) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_IN_USE, "사용 중인 예약만 연장할 수 있습니다");
        }
        Instant now = clock.instant();
        if (now.isBefore(r.endAt().minus(Duration.ofMinutes(policy.openBeforeMinutes()))) || !now.isBefore(r.endAt())) {
            throw new BusinessException(ErrorCode.RESERVATION_EXTENSION_NOT_OPEN,
                    "연장은 종료 %d분 전부터 종료 전까지만 할 수 있습니다".formatted(policy.openBeforeMinutes()));
        }
        checkEnd(r.endAt(), command.endAt());
        if (r.continuedFromId() != null
                || chains.extensionsBefore(userId, r.startAt(), Duration.ofMinutes(policy.chainGapMinutes()))
                        >= policy.maxCount()) {
            throw new BusinessException(ErrorCode.RESERVATION_EXTENSION_LIMIT,
                    "연장은 %d회만 할 수 있습니다".formatted(policy.maxCount()));
        }
        if (!statuses.checkoutIfInUse(reservationId, userId)) {
            // 읽은 뒤 그 사이 퇴실·자동 퇴실됨 (같은 버튼 두 번 등)
            throw new BusinessException(ErrorCode.RESERVATION_NOT_IN_USE, "이미 끝난 예약입니다");
        }
        Instant start = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String code = codes.issue();
        long id = port.insert(new NewReservation(r.roomId(), userId, start, command.endAt(), code, r.participantIds(),
                command.idempotencyKey(), true, command.createdVia(), true, reservationId));
        return new Created(id, r.roomId(), start.atZone(SEOUL), command.endAt().atZone(SEOUL),
                ReservationStatus.IN_USE, r.participantIds().size(), code, false);
    }

    /** 새 끝은 지금 끝 + 30분 단위, 최대 maxMinutes */
    private void checkEnd(Instant currentEnd, Instant endAt) {
        long minutes = Duration.between(currentEnd, endAt).toMinutes();
        boolean onSlot = Duration.between(currentEnd, endAt).toSeconds() % ((long) slots.slotMinutes() * 60) == 0;
        if (minutes <= 0 || minutes > policy.maxMinutes() || !onSlot) {
            throw new BusinessException(ErrorCode.RESERVATION_INVALID_LENGTH,
                    "연장은 지금 종료 시각부터 %d분 단위로 최대 %d분까지 할 수 있습니다"
                            .formatted(slots.slotMinutes(), policy.maxMinutes()));
        }
    }
}
