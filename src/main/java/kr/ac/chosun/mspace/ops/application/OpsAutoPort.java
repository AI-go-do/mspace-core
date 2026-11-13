package kr.ac.chosun.mspace.ops.application;

import java.time.Instant;
import java.util.List;

/** AI 자동 처리 저장소. 구현: JdbcOpsAutoAdapter */
public interface OpsAutoPort {

    boolean isAdmin(long userId);

    List<Row> actions(Instant from, Instant to);

    record Row(long id, String kind, int roomId, String roomName, Long reservationId, Instant startedAt,
               Instant detectedAt, String detail, int notified) {
    }
}
