package kr.ac.chosun.mspace.reservation.adapter.out.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.chosun.mspace.reservation.application.ReservationQueryPort;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 예약 읽기. 내 예약 목록과 취소 실패 이유 찾기 */
@Component
class JdbcReservationQueryAdapter implements ReservationQueryPort {

    private final JdbcClient jdbc;

    JdbcReservationQueryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 내가 들어 있는 예약과 그 예약의 참여자 전원을 한 번에 읽는다 (예약마다 참여자를 따로 읽지 않게).
     * participant_user 인덱스로 내 예약 id를 찾고, 기본 키로 참여자를 붙인다.
     */
    @Override
    public List<Row> findByMember(long userId) {
        Map<Long, Row> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT r.id, r.room_id, rm.name AS room_name, r.start_at, r.end_at, r.status, r.owner_id,
                               r.access_code, p.user_id, u.name AS person_name, p.is_owner
                        FROM reservation r
                        JOIN rooms rm ON rm.id = r.room_id
                        JOIN reservation_participant p ON p.reservation_id = r.id
                        JOIN users u ON u.id = p.user_id
                        WHERE r.id IN (SELECT reservation_id FROM reservation_participant WHERE user_id = :uid)
                        ORDER BY r.id, p.is_owner DESC, p.user_id
                        """)
                .param("uid", userId)
                .query(rs -> {
                    long id = rs.getLong("id");
                    Row row = rows.get(id);
                    if (row == null) {   // 예약의 첫 줄: 예약 정보를 한 번만 만든다
                        row = new Row(id, rs.getInt("room_id"), rs.getString("room_name"),
                                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                                ReservationStatus.valueOf(rs.getString("status")), rs.getLong("owner_id"),
                                rs.getString("access_code"), new ArrayList<>());
                        rows.put(id, row);
                    }
                    row.participants().add(new ParticipantRow(rs.getLong("user_id"), rs.getString("person_name"),
                            rs.getBoolean("is_owner")));
                });
        return List.copyOf(rows.values());
    }

    @Override
    public Optional<Access> findAccess(long reservationId, long userId) {
        return jdbc.sql("""
                        SELECT r.owner_id = :uid AS is_owner, r.status, r.start_at
                        FROM reservation r
                        JOIN reservation_participant p ON p.reservation_id = r.id AND p.user_id = :uid
                        WHERE r.id = :id
                        """)
                .param("id", reservationId)
                .param("uid", userId)
                .query((rs, n) -> new Access(rs.getBoolean("is_owner"), ReservationStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("start_at").toInstant()))
                .optional();
    }

    @Override
    public Optional<CodeMatch> findActiveByCode(int roomId, String accessCode) {
        return jdbc.sql("""
                        SELECT id, status, start_at, end_at FROM reservation
                        WHERE room_id = :room AND access_code = :code AND status IN ('BOOKED', 'IN_USE')
                        """)
                .param("room", roomId)
                .param("code", accessCode)
                .query((rs, n) -> new CodeMatch(rs.getLong("id"), ReservationStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant()))
                .optional();
    }
}
