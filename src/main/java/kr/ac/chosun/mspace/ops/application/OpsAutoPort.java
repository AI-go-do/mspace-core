package kr.ac.chosun.mspace.ops.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.ops.domain.OpsRules.Span;

/** AI 자동 처리 저장소. 구현: JdbcOpsAutoAdapter */
public interface OpsAutoPort {

    boolean isAdmin(long userId);

    /**
     * 사용 중인 방과 재실 상태. 재실 신호가 한 번도 없던 방은 occupied=false, since·presenceReceivedAt=null.
     * lastReceivedAt = 재실·CO2 중 늦게 받은 것(센서 고장 판단), presenceReceivedAt = 재실만(예약 없는 사용 판단)
     */
    List<Room> rooms();

    /** 방마다 [from, to]에 걸치는 예약 (BOOKED·IN_USE·CHECKED_OUT·AUTO_CHECKED_OUT). end = 먼저 퇴실했으면 퇴실 시각 */
    Map<Integer, List<Span>> bookings(Instant from, Instant to);

    /** 처리 기록. 같은 (kind, room, startedAt)이 이미 있으면 false (두 번 처리 안 함) */
    boolean record(String kind, int roomId, Long reservationId, Instant startedAt, Instant now, String detail);

    List<Row> actions(Instant from, Instant to);

    record Room(int id, String name, boolean occupied, Instant since, Instant lastReceivedAt,
                Instant presenceReceivedAt) {
    }

    record Row(long id, String kind, int roomId, String roomName, Long reservationId, Instant startedAt,
               Instant detectedAt, String detail, int notified) {
    }
}
