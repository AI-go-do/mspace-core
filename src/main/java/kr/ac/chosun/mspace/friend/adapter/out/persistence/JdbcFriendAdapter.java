package kr.ac.chosun.mspace.friend.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.friend.application.FriendPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** friendship 테이블 (V15). 한 쌍에 줄 하나(one_friendship_per_pair), 내가 어느 칸(요청자·받는 사람)에 있든 찾는다 */
@Component
class JdbcFriendAdapter implements FriendPort {

    /** 상대 쪽 회원 id: 내가 요청자면 받는 사람, 아니면 요청자 */
    private static final String OTHER = "CASE WHEN f.requester_id = :me THEN f.addressee_id ELSE f.requester_id END";

    private final JdbcClient jdbc;

    JdbcFriendAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Member> findVerified(String studentId) {
        return jdbc.sql("SELECT id, student_id, name FROM users WHERE student_id = :sid AND verified_at IS NOT NULL")
                .param("sid", studentId)
                .query((rs, n) -> new Member(rs.getLong("id"), rs.getString("student_id"), rs.getString("name")))
                .optional();
    }

    @Override
    public Optional<Pair> findPair(long a, long b) {
        return jdbc.sql("""
                        SELECT id, requester_id, addressee_id, status FROM friendship
                        WHERE least(requester_id, addressee_id) = least(:a, :b)
                          AND greatest(requester_id, addressee_id) = greatest(:a, :b)
                        """)
                .param("a", a).param("b", b)
                .query((rs, n) -> new Pair(rs.getLong("id"), rs.getLong("requester_id"), rs.getLong("addressee_id"),
                        "ACCEPTED".equals(rs.getString("status"))))
                .optional();
    }

    @Override
    public Optional<Long> insertPending(long requesterId, long addresseeId, Instant now) {
        // 같은 쌍이 이미 있으면(반대 방향 포함) UNIQUE 인덱스에 걸려 아무것도 넣지 않는다.
        // 동시에 서로 보내면 늦은 쪽은 먼저 넣은 쪽이 커밋할 때까지 기다렸다가 빈 값을 받는다
        return jdbc.sql("""
                        INSERT INTO friendship (requester_id, addressee_id, requested_at)
                        VALUES (:requester, :addressee, :now)
                        ON CONFLICT DO NOTHING
                        RETURNING id
                        """)
                .param("requester", requesterId).param("addressee", addresseeId).param("now", Timestamp.from(now))
                .query(Long.class)
                .optional();
    }

    @Override
    public Optional<Member> accept(long requestId, long addresseeId, Instant now) {
        // 조건부 UPDATE: 내가 받은 대기 중 요청일 때만 바뀐다. 두 번 눌러도 두 번째는 0줄 → 404
        return jdbc.sql("""
                        WITH accepted AS (
                            UPDATE friendship SET status = 'ACCEPTED', accepted_at = :now
                            WHERE id = :id AND addressee_id = :me AND status = 'PENDING'
                            RETURNING requester_id
                        )
                        SELECT u.id, u.student_id, u.name FROM accepted a JOIN users u ON u.id = a.requester_id
                        """)
                .param("id", requestId).param("me", addresseeId).param("now", Timestamp.from(now))
                .query((rs, n) -> new Member(rs.getLong("id"), rs.getString("student_id"), rs.getString("name")))
                .optional();
    }

    @Override
    public boolean deletePending(long requestId, long userId, boolean asAddressee) {
        String who = asAddressee ? "addressee_id" : "requester_id";
        return jdbc.sql("DELETE FROM friendship WHERE id = :id AND " + who + " = :me AND status = 'PENDING'")
                .param("id", requestId).param("me", userId)
                .update() == 1;
    }

    @Override
    public boolean deleteAccepted(long a, long b) {
        return jdbc.sql("""
                        DELETE FROM friendship
                        WHERE least(requester_id, addressee_id) = least(:a, :b)
                          AND greatest(requester_id, addressee_id) = greatest(:a, :b)
                          AND status = 'ACCEPTED'
                        """)
                .param("a", a).param("b", b)
                .update() == 1;
    }

    @Override
    public List<FriendRow> friends(long me) {
        return jdbc.sql("""
                        SELECT u.student_id, u.name, f.accepted_at
                        FROM friendship f JOIN users u ON u.id = %s
                        WHERE (f.requester_id = :me OR f.addressee_id = :me) AND f.status = 'ACCEPTED'
                        ORDER BY u.student_id
                        """.formatted(OTHER))
                .param("me", me)
                .query((rs, n) -> new FriendRow(rs.getString("student_id"), rs.getString("name"),
                        rs.getTimestamp("accepted_at").toInstant()))
                .list();
    }

    @Override
    public List<RequestRow> received(long me) {
        return requests("f.addressee_id = :me", "f.requester_id", me);
    }

    @Override
    public List<RequestRow> sent(long me) {
        return requests("f.requester_id = :me", "f.addressee_id", me);
    }

    private List<RequestRow> requests(String mine, String otherColumn, long me) {
        return jdbc.sql("""
                        SELECT f.id, u.student_id, u.name, f.requested_at
                        FROM friendship f JOIN users u ON u.id = %s
                        WHERE %s AND f.status = 'PENDING'
                        ORDER BY f.requested_at DESC, f.id DESC
                        """.formatted(otherColumn, mine))
                .param("me", me)
                .query((rs, n) -> new RequestRow(rs.getLong("id"), rs.getString("student_id"), rs.getString("name"),
                        rs.getTimestamp("requested_at").toInstant()))
                .list();
    }

    @Override
    public int countFriends(long ownerId, Collection<Long> ids) {
        return jdbc.sql("""
                        SELECT count(*) FROM friendship f
                        WHERE f.status = 'ACCEPTED'
                          AND ((f.requester_id = :me AND f.addressee_id IN (:ids))
                            OR (f.addressee_id = :me AND f.requester_id IN (:ids)))
                        """)
                .param("me", ownerId).param("ids", ids)
                .query(Integer.class)
                .single();
    }
}
