package kr.ac.chosun.mspace.report.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import kr.ac.chosun.mspace.report.application.ReportPort;
import kr.ac.chosun.mspace.report.domain.ReportCategory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 신고 저장. 권한 판단은 V1__init_schema.sql 끝 "8-1. 신고 권한"과 같다 */
@Component
class JdbcReportAdapter implements ReportPort {

    private final JdbcClient jdbc;

    JdbcReportAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> findBasisReservation(long userId, int roomId) {
        // 참여자 줄에는 대표자도 들어 있다(is_owner). 한 사람은 같은 시간에 두 방에 못 들어가서(no_person_overlap) 보통 한 줄이다
        return jdbc.sql("""
                        SELECT r.id
                        FROM reservation r
                        JOIN reservation_participant p ON p.reservation_id = r.id AND p.user_id = :user
                        WHERE r.status = 'IN_USE'
                          AND (r.room_id = :room
                               OR EXISTS (SELECT 1 FROM room_adjacency a
                                          WHERE a.room_id = r.room_id AND a.adjacent_room_id = :room))
                        ORDER BY (r.room_id = :room) DESC, r.id
                        LIMIT 1
                        """)
                .param("user", userId)
                .param("room", roomId)
                .query(Long.class)
                .optional();
    }

    @Override
    public Saved save(int roomId, long reporterId, long basisReservationId, ReportCategory category, String content) {
        return jdbc.sql("""
                        INSERT INTO report (room_id, reporter_id, reporter_reservation_id, category, content)
                        VALUES (:room, :reporter, :basis, :category, :content)
                        RETURNING id, created_at
                        """)
                .param("room", roomId)
                .param("reporter", reporterId)
                .param("basis", basisReservationId)
                .param("category", category.name())
                .param("content", content)
                .query((rs, n) -> new Saved(rs.getLong("id"), rs.getTimestamp("created_at").toInstant()))
                .single();
    }
}
