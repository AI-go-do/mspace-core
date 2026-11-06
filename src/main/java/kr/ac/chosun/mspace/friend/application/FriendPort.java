package kr.ac.chosun.mspace.friend.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 친구에 필요한 DB 일. 구현은 adapter/out/persistence. 이름은 실명 그대로 주고, 가리기는 서비스가 한다 */
public interface FriendPort {

    /** 학생 인증을 마친 회원 */
    Optional<Member> findVerified(String studentId);

    /** 두 회원 사이의 줄(누가 먼저 보냈든). 없으면 빈 값 */
    Optional<Pair> findPair(long a, long b);

    /** 대기 중 요청을 넣는다. 그 쌍에 이미 줄이 있으면(동시 요청 포함) 빈 값 */
    Optional<Long> insertPending(long requesterId, long addresseeId, Instant now);

    /** addresseeId가 받은 대기 중 요청이면 수락하고 상대(요청한 사람)를 돌려준다. 아니면 빈 값 */
    Optional<Member> accept(long requestId, long addresseeId, Instant now);

    /** 대기 중 요청을 지운다. asAddressee면 받은 사람으로, 아니면 보낸 사람으로 확인. 지웠으면 true */
    boolean deletePending(long requestId, long userId, boolean asAddressee);

    /** 수락된 친구 줄을 지운다. 지웠으면 true */
    boolean deleteAccepted(long a, long b);

    List<FriendRow> friends(long me);

    List<RequestRow> received(long me);

    List<RequestRow> sent(long me);

    /** ids 중 ownerId와 수락된 친구인 사람 수 */
    int countFriends(long ownerId, Collection<Long> ids);

    record Member(long id, String studentId, String name) {
    }

    record Pair(long id, long requesterId, long addresseeId, boolean accepted) {
    }

    record FriendRow(String studentId, String name, Instant since) {
    }

    record RequestRow(long requestId, String studentId, String name, Instant requestedAt) {
    }
}
