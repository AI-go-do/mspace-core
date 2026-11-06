package kr.ac.chosun.mspace.friend.application;

import java.time.Instant;
import java.util.List;

/**
 * 친구 (FR-RSV-004 변경, 11/6 지영 결정). 학번으로 요청 → 받은 사람이 수락하면 친구.
 * 친구는 삭제할 때까지 계속 남고, 예약 참여자는 친구만 넣을 수 있다.
 * 이름은 모두 가린 이름(김*영)으로만 돌려준다.
 */
public interface FriendUseCase {

    /** 친구 창: 친구 목록, 받은 요청, 보낸 요청 */
    FriendList list(long me);

    /**
     * 학번으로 친구 요청. 상대가 이미 나에게 요청해 두었으면 그 자리에서 친구가 된다(ACCEPTED).
     *
     * @throws kr.ac.chosun.mspace.common.error.BusinessException 없는 학번 404, 나 자신 400, 이미 친구·이미 보냄 409
     */
    RequestResult request(long me, String studentId);

    /** 받은 요청 수락. 내가 받은 대기 중 요청이 아니면 404 */
    Friend accept(long me, long requestId);

    /** 받은 요청 거절(요청이 지워져 상대가 다시 보낼 수 있다). 내가 받은 대기 중 요청이 아니면 404 */
    void decline(long me, long requestId);

    /** 보낸 요청 취소. 내가 보낸 대기 중 요청이 아니면 404 */
    void cancel(long me, long requestId);

    /** 친구 삭제(양쪽 목록에서 같이 빠진다). 친구가 아니면 404. 이미 잡은 예약의 참여자는 그대로 */
    void remove(long me, String studentId);

    record Friend(String studentId, String name, Instant since) {
    }

    record Request(long requestId, String studentId, String name, Instant requestedAt) {
    }

    record FriendList(List<Friend> friends, List<Request> received, List<Request> sent) {
    }

    /** status: PENDING(요청 보냄) 또는 ACCEPTED(상대가 먼저 보내 둬서 바로 친구) */
    record RequestResult(long requestId, String studentId, String name, String status) {
    }
}
