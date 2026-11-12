package kr.ac.chosun.mspace.room.adapter.in.web;

import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.room.application.RoomWatchUseCase;
import kr.ac.chosun.mspace.room.application.RoomWatchUseCase.Watch;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 빈방 알림 ("비면 알려 줘요"). 오늘 하루만, 비면 알림함으로 한 번 */
@RestController
class RoomWatchController {

    private final RoomWatchUseCase watches;

    RoomWatchController(RoomWatchUseCase watches) {
        this.watches = watches;
    }

    /** 오늘 켜 둔 방. 200 [{"roomId":2,"since":"…+09:00"}] */
    @GetMapping("/api/room-watches/me")
    List<Watch> mine(LoginUser user) {
        return watches.myWatches(user.id());
    }

    /** 켜기 {"roomId":2}. 200 (이미 켜져 있어도) / 404 방 없음 / 409 ROOM_WATCH_FREE 지금 비어 있음 */
    @PostMapping("/api/room-watches")
    Watch watch(LoginUser user, @RequestBody WatchRequest request) {
        if (request == null || request.roomId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return watches.watch(user.id(), request.roomId());
    }

    /** 끄기. 204 (켜져 있지 않아도) */
    @PostMapping("/api/room-watches/{roomId}/cancel")
    ResponseEntity<Void> unwatch(LoginUser user, @PathVariable int roomId) {
        watches.unwatch(user.id(), roomId);
        return ResponseEntity.noContent().build();
    }

    record WatchRequest(Integer roomId) {
    }
}
