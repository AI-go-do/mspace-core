package kr.ac.chosun.mspace.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.common.config.AccessPolicy;
import kr.ac.chosun.mspace.reservation.application.KeypadEntryUseCase.Entry;
import kr.ac.chosun.mspace.reservation.application.KeypadEntryUseCase.Result;
import kr.ac.chosun.mspace.reservation.application.ReservationQueryPort.CodeMatch;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 번호를 읽은 뒤 체크인하기 전에 상태가 바뀐 경우만 따로 본다. DB 없이 가짜 포트로 순서를 정해 둔다.
 * (실제 DB 흐름은 KeypadAndCheckoutApiTest)
 */
class KeypadEntryServiceTest {

    // 지금 = 11/04 13:55 서울, 예약 = 14:00~16:00 (번호 유효 시간 안)
    final Clock clock = Clock.fixed(Instant.parse("2026-11-04T04:55:00Z"), ZoneId.of("Asia/Seoul"));
    final Instant start = Instant.parse("2026-11-04T05:00:00Z");
    final Instant end = Instant.parse("2026-11-04T07:00:00Z");
    final AccessPolicy policy = new AccessPolicy(10, 5, 60, "t", "", false);

    /** findActiveByCode가 부를 때마다 차례로 답한다 */
    static class FakeQueries implements ReservationQueryPort {
        final Deque<Optional<CodeMatch>> answers = new ArrayDeque<>();

        @Override
        public List<Row> findByMember(long userId) {
            return List.of();
        }

        @Override
        public Optional<Access> findAccess(long reservationId, long userId) {
            return Optional.empty();
        }

        @Override
        public Optional<CodeMatch> findActiveByCode(int roomId, String accessCode) {
            return answers.isEmpty() ? Optional.empty() : answers.poll();
        }
    }

    /** 체크인은 정해 둔 값을 돌려준다 */
    record FakeStatuses(boolean checkInResult) implements ReservationStatusPort {
        @Override
        public boolean cancelIfBookedBeforeStart(long reservationId, long ownerId, int instantCancelMinutes) {
            return false;
        }

        @Override
        public boolean checkInIfBooked(long reservationId, Instant now, int openBeforeMinutes) {
            return checkInResult;
        }

        @Override
        public boolean checkoutIfInUse(long reservationId, long ownerId, Instant now) {
            return false;
        }
    }

    @Test
    @DisplayName("BOOKED를 읽은 뒤 대표자가 취소해 체크인이 0줄이면 문을 열지 않는다")
    void canceledBetweenReadAndCheckInIsDenied() {
        // given: 처음엔 BOOKED, 체크인은 0줄, 다시 읽으면 취소돼서 없음
        FakeQueries queries = new FakeQueries();
        queries.answers.add(Optional.of(new CodeMatch(7, ReservationStatus.BOOKED, start, end, start.plusSeconds(900))));
        queries.answers.add(Optional.empty());
        KeypadEntryService service = new KeypadEntryService(queries, new FakeStatuses(false), policy, clock);

        // when
        Entry entry = service.enter(1, "123456");

        // then
        assertThat(entry.result()).isEqualTo(Result.WRONG_CODE);
    }

    @Test
    @DisplayName("BOOKED를 읽은 뒤 다른 입력이 먼저 체크인해 0줄이면, 이미 사용 중이라 문은 연다")
    void checkedInByOtherEntryStillOpens() {
        // given: 처음엔 BOOKED, 체크인은 0줄, 다시 읽으면 IN_USE
        FakeQueries queries = new FakeQueries();
        queries.answers.add(Optional.of(new CodeMatch(7, ReservationStatus.BOOKED, start, end, start.plusSeconds(900))));
        queries.answers.add(Optional.of(new CodeMatch(7, ReservationStatus.IN_USE, start, end, start.plusSeconds(900))));
        KeypadEntryService service = new KeypadEntryService(queries, new FakeStatuses(false), policy, clock);

        // when
        Entry entry = service.enter(1, "123456");

        // then
        assertThat(entry.result()).isEqualTo(Result.OPENED);
        assertThat(entry.reservationId()).isEqualTo(7);
    }
}
