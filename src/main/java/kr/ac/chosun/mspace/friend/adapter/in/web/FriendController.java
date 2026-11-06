package kr.ac.chosun.mspace.friend.adapter.in.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.friend.application.FriendUseCase;
import kr.ac.chosun.mspace.friend.application.FriendUseCase.Friend;
import kr.ac.chosun.mspace.friend.application.FriendUseCase.Request;
import kr.ac.chosun.mspace.friend.application.FriendUseCase.RequestResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 친구 API 입구 (FR-RSV-004 변경, 11/6 지영 결정). 서로 수락해야 친구, 친구만 예약 참여자로 넣을 수 있다.
 * 모두 로그인한 회원만(없으면 401). 이름은 가린 이름(김*영).
 */
@RestController
class FriendController {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final FriendUseCase friends;

    FriendController(FriendUseCase friends) {
        this.friends = friends;
    }

    /**
     * 친구 창. 예: GET /api/friends →
     * {"friends":[{"studentId":"20230002","name":"박*수","since":"…+09:00"}],
     *  "received":[{"requestId":7,"studentId":"20230003","name":"이*","requestedAt":"…+09:00"}], "sent":[]}
     */
    @GetMapping("/api/friends")
    ResponseEntity<FriendListResponse> list(LoginUser user) {
        var l = friends.list(user.id());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new FriendListResponse(
                l.friends().stream().map(FriendResponse::from).toList(),
                l.received().stream().map(RequestResponse::from).toList(),
                l.sent().stream().map(RequestResponse::from).toList()));
    }

    /**
     * 학번으로 친구 요청. 예: POST /api/friends/requests {"studentId":"20230002"}
     * → 201 {"requestId":7,"studentId":"20230002","name":"박*수","status":"PENDING"}.
     * 상대가 이미 나에게 보내 둔 요청이 있으면 바로 친구가 되어 200, status "ACCEPTED".
     * 없는 학번 404 MEMBER_NOT_FOUND, 나 자신 400 FRIEND_SELF, 이미 친구 409 FRIEND_ALREADY, 이미 보냄 409 FRIEND_REQUEST_ALREADY_SENT
     */
    @PostMapping("/api/friends/requests")
    ResponseEntity<RequestResult> request(LoginUser user, @RequestBody(required = false) FriendRequestBody body) {
        RequestResult r = friends.request(user.id(), body == null ? null : body.studentId());
        return ResponseEntity.status("PENDING".equals(r.status()) ? HttpStatus.CREATED : HttpStatus.OK).body(r);
    }

    /** 받은 요청 수락 → 200 친구. 내가 받은 대기 중 요청이 아니면 404 FRIEND_REQUEST_NOT_FOUND */
    @PostMapping("/api/friends/requests/{id}/accept")
    FriendResponse accept(LoginUser user, @PathVariable long id) {
        return FriendResponse.from(friends.accept(user.id(), id));
    }

    /** 받은 요청 거절 → 204. 상대는 나중에 다시 보낼 수 있다 */
    @PostMapping("/api/friends/requests/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(LoginUser user, @PathVariable long id) {
        friends.decline(user.id(), id);
    }

    /** 보낸 요청 취소 → 204 */
    @PostMapping("/api/friends/requests/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(LoginUser user, @PathVariable long id) {
        friends.cancel(user.id(), id);
    }

    /** 친구 삭제 → 204 (양쪽 목록에서 빠짐). 친구가 아니면 404 FRIEND_NOT_FOUND. 이미 잡은 예약은 그대로 */
    @DeleteMapping("/api/friends/{studentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(LoginUser user, @PathVariable String studentId) {
        friends.remove(user.id(), studentId);
    }

    record FriendRequestBody(String studentId) {
    }

    record FriendListResponse(List<FriendResponse> friends, List<RequestResponse> received, List<RequestResponse> sent) {
    }

    record FriendResponse(String studentId, String name, OffsetDateTime since) {
        static FriendResponse from(Friend f) {
            return new FriendResponse(f.studentId(), f.name(), seoul(f.since()));
        }
    }

    record RequestResponse(long requestId, String studentId, String name, OffsetDateTime requestedAt) {
        static RequestResponse from(Request r) {
            return new RequestResponse(r.requestId(), r.studentId(), r.name(), seoul(r.requestedAt()));
        }
    }

    private static OffsetDateTime seoul(Instant t) {
        return t.atZone(SEOUL).toOffsetDateTime();
    }
}
