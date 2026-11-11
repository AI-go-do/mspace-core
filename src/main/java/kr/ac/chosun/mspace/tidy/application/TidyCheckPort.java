package kr.ac.chosun.mspace.tidy.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.CurrentReservation;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.LastEnded;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Listed;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Phase;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Result;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.TidyCheck;

/** 표는 V19__tidy_check.sql, V26__tidy_recheck.sql. 구현: JdbcTidyCheckAdapter */
public interface TidyCheckPort {

    boolean roomExists(int roomId);

    Optional<CurrentReservation> current(int roomId, Instant now);

    Optional<LastEnded> lastEnded(int roomId);

    /** "찾으러 갈게요" QR로 문을 한 번이라도 열었는지 (retrieve_pass.first_used_at) */
    boolean retrieveUsed(long reservationId);

    /** 이 방에서 그 예약 말고 since 뒤에 입실한 예약이 있는지 (지금 사용 중이든 이미 나갔든) */
    boolean enteredSince(int roomId, long reservationId, Instant since);

    /** 이 방 예약의 상태. 같은 예약의 결과가 동시에 두 번 와도 한 줄로 서게 예약 줄을 잠근다 */
    Optional<String> lockReservation(int roomId, long reservationId);

    /** 이미 받은 결과. reportId는 11/10 전 NEEDS_CLEANING 기록만 */
    record Existing(long reservationId, Phase phase, Long reportId) {
    }

    Optional<Existing> findByEvent(String eventId);

    /** 그 예약의 그 단계 결과. 없으면 empty */
    Optional<Result> result(long reservationId, Phase phase);

    /** @param reportId RECHECK에서 경고 근거로 만든 카메라 신고. 그 밖에는 null */
    void insert(TidyCheck check, Long reportId, Instant receivedAt);

    /** 대표자 먼저 */
    List<Long> members(long reservationId);

    String roomName(int roomId);

    boolean isAdmin(long userId);

    /** since 이후에 받은 결과, 받은 시각 최근 순 (tidy_check_recent 인덱스) */
    List<Listed> receivedSince(Instant since);
}
