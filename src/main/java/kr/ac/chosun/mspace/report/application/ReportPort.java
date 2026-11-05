package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.report.domain.ReportCategory;

/** 신고 저장소 출력 포트. 구현: JdbcReportAdapter */
public interface ReportPort {

    /**
     * 신고 권한 근거 예약을 찾는다: 이 회원이 이용자(대표자·참여자)로 들어간 IN_USE 예약 중,
     * 방이 신고 대상 방과 같거나 옆 방(room_adjacency)인 것. 같은 방 예약을 먼저 고른다.
     *
     * @return 근거 예약 id. 없으면 신고할 수 없다
     */
    Optional<Long> findBasisReservation(long userId, int roomId);

    /** 방이 있는지 (사용 중지된 방도 있음으로 본다) */
    boolean roomExists(int roomId);

    Saved save(int roomId, long reporterId, long basisReservationId, ReportCategory category, String content);

    record Saved(long id, Instant createdAt) {
    }
}
