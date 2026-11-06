package kr.ac.chosun.mspace.friend.application;

import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.common.privacy.MaskedName;
import kr.ac.chosun.mspace.friend.application.FriendPort.Member;
import kr.ac.chosun.mspace.friend.application.FriendPort.Pair;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FriendService implements FriendUseCase, FriendCheck {

    /** users.student_id 칸 길이 (V1 varchar(20)) */
    private static final int STUDENT_ID_MAX = 20;

    private final FriendPort port;
    private final Clock clock;
    private final boolean friendsOnly;

    FriendService(FriendPort port, Clock clock, @Value("${mspace.reservation.friends-only:true}") boolean friendsOnly) {
        this.port = port;
        this.clock = clock;
        this.friendsOnly = friendsOnly;
    }

    @Override
    @Transactional(readOnly = true)
    public FriendList list(long me) {
        return new FriendList(
                port.friends(me).stream().map(f -> new Friend(f.studentId(), MaskedName.of(f.name()), f.since())).toList(),
                port.received(me).stream().map(FriendService::masked).toList(),
                port.sent(me).stream().map(FriendService::masked).toList());
    }

    @Override
    @Transactional
    public RequestResult request(long me, String studentId) {
        String id = studentId == null ? "" : studentId.strip();
        if (id.isEmpty() || id.length() > STUDENT_ID_MAX) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "학번을 1–20자로 넣어 주세요");
        }
        Member target = port.findVerified(id).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (target.id() == me) {
            throw new BusinessException(ErrorCode.FRIEND_SELF);
        }
        // 먼저 넣어 본다. 그 쌍에 줄이 이미 있으면(동시에 서로 보낸 경우 포함) UNIQUE 인덱스가 막고, 있는 줄을 보고 정한다
        var inserted = port.insertPending(me, target.id(), clock.instant());
        if (inserted.isPresent()) {
            return new RequestResult(inserted.get(), target.studentId(), MaskedName.of(target.name()), "PENDING");
        }
        Pair pair = port.findPair(me, target.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.FRIEND_REQUEST_NOT_FOUND)); // 그 사이 지워짐(드묾)
        if (pair.accepted()) {
            throw new BusinessException(ErrorCode.FRIEND_ALREADY);
        }
        if (pair.requesterId() == me) {
            throw new BusinessException(ErrorCode.FRIEND_REQUEST_ALREADY_SENT);
        }
        // 상대가 먼저 나에게 보내 둔 요청 → 내 요청을 수락으로 본다
        port.accept(pair.id(), me, clock.instant())
                .orElseThrow(() -> new BusinessException(ErrorCode.FRIEND_REQUEST_NOT_FOUND));
        return new RequestResult(pair.id(), target.studentId(), MaskedName.of(target.name()), "ACCEPTED");
    }

    @Override
    @Transactional
    public Friend accept(long me, long requestId) {
        Member requester = port.accept(requestId, me, clock.instant())
                .orElseThrow(() -> new BusinessException(ErrorCode.FRIEND_REQUEST_NOT_FOUND));
        return new Friend(requester.studentId(), MaskedName.of(requester.name()), clock.instant());
    }

    @Override
    @Transactional
    public void decline(long me, long requestId) {
        if (!port.deletePending(requestId, me, true)) {
            throw new BusinessException(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public void cancel(long me, long requestId) {
        if (!port.deletePending(requestId, me, false)) {
            throw new BusinessException(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public void remove(long me, String studentId) {
        String id = studentId == null ? "" : studentId.strip();
        Member other = port.findVerified(id).orElseThrow(() -> new BusinessException(ErrorCode.FRIEND_NOT_FOUND));
        if (!port.deleteAccepted(me, other.id())) {
            throw new BusinessException(ErrorCode.FRIEND_NOT_FOUND);
        }
    }

    @Override
    public boolean canAddAsParticipants(long ownerId, Collection<Long> memberIds) {
        if (!friendsOnly || memberIds.isEmpty()) {
            return true;
        }
        var distinct = new HashSet<>(memberIds);
        return port.countFriends(ownerId, distinct) == distinct.size();
    }

    private static Request masked(FriendPort.RequestRow r) {
        return new Request(r.requestId(), r.studentId(), MaskedName.of(r.name()), r.requestedAt());
    }
}
