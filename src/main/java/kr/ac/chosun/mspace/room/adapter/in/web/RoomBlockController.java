package kr.ac.chosun.mspace.room.adapter.in.web;

import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.room.application.BlockReason;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.Block;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.Canceled;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.Conflict;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.Created;
import kr.ac.chosun.mspace.room.application.RoomBlockUseCase.NewBlock;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 기간 막기 API (Q-23, core #95). 모두 관리자만, 아니면 403 ADMIN_ONLY */
@RestController
class RoomBlockController {

    private final RoomBlockUseCase blocks;

    RoomBlockController(RoomBlockUseCase blocks) {
        this.blocks = blocks;
    }

    /** 아직 안 끝났거나 끝난 지 30일 안인 막기 (풀린 것 포함, releasedAt으로 구분) */
    @GetMapping("/api/admin/room-blocks")
    List<Block> list(LoginUser user) {
        return blocks.list(user.id());
    }

    /**
     * 막기. 예: {"roomId":5,"startAt":"2030-03-06T09:00:00+09:00","endAt":"2030-03-06T12:00:00+09:00",
     * "reason":"INSPECTION","memo":"에어컨 점검"} → 201 {block, conflicts}.
     * 30분 칸이 아니거나 지난 기간·31일 초과·메모 100자 초과면 400, 없는 방 404, 같은 방 막기와 겹치면 409
     */
    @PostMapping("/api/admin/room-blocks")
    @ResponseStatus(HttpStatus.CREATED)
    Created create(LoginUser user, @RequestBody BlockRequest request) {
        if (request.roomId() == null) {
            // int로 받으면 빠진 roomId가 0이 되어 404(ROOM_NOT_FOUND)가 난다. 빠진 값은 입력 오류로 (수연 리뷰)
            throw new BusinessException(ErrorCode.ROOM_BLOCK_INVALID, "방을 골라 주세요");
        }
        return blocks.create(user.id(), new NewBlock(request.roomId(),
                request.startAt() == null ? null : request.startAt().toInstant(),
                request.endAt() == null ? null : request.endAt().toInstant(), request.reason(), request.memo()));
    }

    /** 풀기. 이미 풀렸으면 그대로 200 */
    @PostMapping("/api/admin/room-blocks/{id}/release")
    Block release(LoginUser user, @PathVariable long id) {
        return blocks.release(user.id(), id);
    }

    /** 이 막기와 겹친 끝나지 않은 예약 */
    @GetMapping("/api/admin/room-blocks/{id}/conflicts")
    List<Conflict> conflicts(LoginUser user, @PathVariable long id) {
        return blocks.conflicts(user.id(), id);
    }

    /** 겹친 예약 중 시작 전인 것 취소 + 대표자·참여자 알림. → {canceled, reservationIds} */
    @PostMapping("/api/admin/room-blocks/{id}/cancel-conflicts")
    Canceled cancelConflicts(LoginUser user, @PathVariable long id) {
        return blocks.cancelConflicts(user.id(), id);
    }

    record BlockRequest(Integer roomId, OffsetDateTime startAt, OffsetDateTime endAt, BlockReason reason, String memo) {
    }
}
