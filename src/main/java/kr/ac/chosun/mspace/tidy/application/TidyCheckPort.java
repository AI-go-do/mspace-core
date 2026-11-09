package kr.ac.chosun.mspace.tidy.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.CurrentReservation;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.LastEnded;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.Listed;
import kr.ac.chosun.mspace.tidy.application.TidyCheckUseCase.TidyCheck;

/** 표는 V19__tidy_check.sql. 구현: JdbcTidyCheckAdapter */
public interface TidyCheckPort {

    boolean roomExists(int roomId);

    Optional<CurrentReservation> current(int roomId, Instant now);

    Optional<LastEnded> lastEnded(int roomId);

    /** 이 방 예약의 상태. 같은 예약의 결과가 동시에 두 번 와도 한 줄로 서게 예약 줄을 잠근다 */
    Optional<String> lockReservation(int roomId, long reservationId);

    /** 이미 받은 결과. reportId는 깨끗했으면 null */
    record Existing(long reservationId, Long reportId) {
    }

    Optional<Existing> findByEvent(String eventId);

    boolean existsForReservation(long reservationId);

    void insert(TidyCheck check, Long reportId, Instant receivedAt);

    /** 대표자 먼저 */
    List<Long> members(long reservationId);

    String roomName(int roomId);

    boolean isAdmin(long userId);

    /** since 이후에 받은 결과, 받은 시각 최근 순 (tidy_check_recent 인덱스) */
    List<Listed> receivedSince(Instant since);
}
