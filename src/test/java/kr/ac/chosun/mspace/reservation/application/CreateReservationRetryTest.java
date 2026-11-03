package kr.ac.chosun.mspace.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.chosun.mspace.common.config.ReservationPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Command;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import kr.ac.chosun.mspace.room.application.RoomAvailabilityPort;
import kr.ac.chosun.mspace.room.application.RoomQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 교착 다시 하기만 따로 본다. DB 없이, insert가 정해진 횟수만큼 교착 예외를 던지는 가짜 포트를 쓴다.
 * (실제 교착 재현은 ConcurrentReservationTest의 "같은 참여자·다른 방" 100건 테스트)
 */
class CreateReservationRetryTest {

    /** insert를 부를 때마다 센다. 처음 deadlocks번은 교착 예외, 그다음은 성공(id 42) */
    static class FakePort implements ReservationCommandPort {
        final int deadlocks;
        int insertCalls;

        FakePort(int deadlocks) {
            this.deadlocks = deadlocks;
        }

        @Override
        public Optional<String> findVerifiedStudentId(long userId) {
            return Optional.of("2024000001");
        }

        @Override
        public Map<String, Long> findVerifiedUserIds(Collection<String> studentIds) {
            return Map.of("2024000002", 2L, "2024000003", 3L);
        }

        @Override
        public Optional<KeyedReservation> findByIdempotencyKey(long ownerId, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public long insert(NewReservation reservation) {
            insertCalls++;
            if (insertCalls <= deadlocks) {
                throw new PessimisticLockingFailureException("deadlock detected (40P01)");
            }
            return 42;
        }
    }

    // 지금 = 2026-11-03 10:10 서울, 예약 = 11/04 14:00~16:00
    final Clock clock = Clock.fixed(Instant.parse("2026-11-03T01:10:00Z"), ZoneId.of("Asia/Seoul"));
    final Command command = new Command(1, Instant.parse("2026-11-04T05:00:00Z"), Instant.parse("2026-11-04T07:00:00Z"),
            List.of("2024000002", "2024000003"));

    /** 방은 언제나 1번 방(3~5명)이 있다고 답하는 가짜 */
    final RoomAvailabilityPort rooms = new RoomAvailabilityPort() {
        @Override
        public List<RoomInfo> findActiveRooms() {
            return List.of(new RoomInfo(1, "1번 방", 3, 5));
        }

        @Override
        public Optional<RoomInfo> findActiveRoom(int roomId) {
            return Optional.of(new RoomInfo(roomId, "1번 방", 3, 5));
        }

        @Override
        public List<BookedTime> findActiveReservations(Instant from, Instant to) {
            return List.of();
        }
    };

    CreateReservationService service(FakePort port) {
        // 번호 발급은 인터페이스라 테스트에서 고정 번호를 끼운다
        return new CreateReservationService(port, new RoomQuery(rooms), () -> "123456",
                new ReservationPolicy(7, 30, 120), clock, TransactionOperations.withoutTransaction());
    }

    @Test
    @DisplayName("교착이 두 번 나도 세 번째에 저장되면 예약이 만들어진다")
    void retriesUntilSuccess() {
        // given
        FakePort port = new FakePort(2);

        // when
        CreateReservationUseCase.Created created = service(port).create(1, command);

        // then
        assertThat(created.id()).isEqualTo(42);
        assertThat(created.accessCode()).isEqualTo("123456");
        assertThat(port.insertCalls).isEqualTo(3);
    }

    @Test
    @DisplayName("교착이 세 번 계속되면 더 하지 않고 503(RESERVATION_BUSY)으로 끝난다")
    void givesUpAfterMaxAttempts() {
        // given
        FakePort port = new FakePort(Integer.MAX_VALUE);

        // when, then
        assertThatThrownBy(() -> service(port).create(1, command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.RESERVATION_BUSY);
        assertThat(port.insertCalls).isEqualTo(CreateReservationService.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("409 같은 업무 오류는 다시 하지 않는다")
    void doesNotRetryBusinessErrors() {
        // given: insert가 겹침(409)으로 실패
        FakePort port = new FakePort(0) {
            @Override
            public long insert(NewReservation reservation) {
                insertCalls++;
                throw new BusinessException(ErrorCode.RESERVATION_TIME_TAKEN);
            }
        };

        // when, then
        assertThatThrownBy(() -> service(port).create(1, command)).isInstanceOf(BusinessException.class);
        assertThat(port.insertCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("뽑은 키패드 번호가 같은 방 다른 예약과 겹치면 새 번호로 다시 해서 저장한다")
    void reissuesTakenAccessCode() {
        // given: 첫 저장은 번호 겹침(one_active_code_per_room), 두 번째는 성공. 번호는 부를 때마다 다르게
        FakePort port = new FakePort(0) {
            @Override
            public long insert(NewReservation reservation) {
                insertCalls++;
                if (insertCalls == 1) {
                    throw new BusinessException(ErrorCode.ACCESS_CODE_TAKEN);
                }
                return 42;
            }
        };
        int[] issued = {0};
        CreateReservationService service = new CreateReservationService(port, new RoomQuery(rooms),
                () -> "00000" + (++issued[0]), new ReservationPolicy(7, 30, 120), clock,
                TransactionOperations.withoutTransaction());

        // when
        CreateReservationUseCase.Created created = service.create(1, command);

        // then
        assertThat(port.insertCalls).isEqualTo(2);
        assertThat(created.accessCode()).isEqualTo("000002");
    }

    @Test
    @DisplayName("같은 키 요청이 동시에 와서 저장이 409로 지면, 키로 다시 찾아 앞 요청의 예약을 200(replayed)으로 돌려준다")
    void replaysAfterKeyConflict() {
        // given: 처음 찾을 땐 없음 → 저장은 키 유일 제약 409 → 다시 찾으면 앞 요청이 만든 예약(id 7)이 있음
        Command keyed = new Command(command.roomId(), command.startAt(), command.endAt(),
                command.participantStudentIds(), "same-key");
        int[] lookups = {0};
        FakePort port = new FakePort(0) {
            @Override
            public Optional<KeyedReservation> findByIdempotencyKey(long ownerId, String idempotencyKey) {
                lookups[0]++;
                if (lookups[0] == 1) {
                    return Optional.empty();
                }
                return Optional.of(new KeyedReservation(7, keyed.roomId(), keyed.startAt(), keyed.endAt(),
                        ReservationStatus.BOOKED, 3, "654321", keyed.participantStudentIds()));
            }

            @Override
            public long insert(NewReservation reservation) {
                insertCalls++;
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
        };

        // when
        CreateReservationUseCase.Created created = service(port).create(1, keyed);

        // then
        assertThat(created.replayed()).isTrue();
        assertThat(created.id()).isEqualTo(7);
        assertThat(created.accessCode()).isEqualTo("654321");
        assertThat(lookups[0]).isEqualTo(2);
        assertThat(port.insertCalls).isEqualTo(1);
    }
}
