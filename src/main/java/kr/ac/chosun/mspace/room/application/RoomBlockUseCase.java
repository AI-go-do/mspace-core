package kr.ac.chosun.mspace.room.application;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 관리자 기간 막기 (Q-23, core #95). 모두 관리자만(아니면 403 ADMIN_ONLY).
 * 막은 칸은 학생이 예약·즉시 시작·연장할 수 없다(예약 저장 때 409 ROOM_BLOCKED).
 * 그 기간에 이미 잡힌 예약은 바로 지우지 않고 목록으로 보여 주고, 관리자가 확인하면 취소 + 대표자·참여자 알림.
 */
public interface RoomBlockUseCase {

    /** 한 번에 막을 수 있는 최대 일수. 잘못 눌러 몇 달을 막지 않게 (더 길면 나눠서) */
    int MAX_DAYS = 31;

    /** 메모 최대 글자. 학생에게도 보이므로 이름·학번은 적지 않는다 */
    int MEMO_MAX = 100;

    /** 끝난 지 이 일수 안의 막기까지 목록에 보인다 (풀린 것 포함) */
    int LIST_PAST_DAYS = 30;

    /** 아직 안 끝났거나 끝난 지 {@value #LIST_PAST_DAYS}일 안인 막기. 시작 빠른 순 */
    List<Block> list(long adminId);

    /** 막고, 그 기간과 겹친 끝나지 않은 예약을 같이 돌려준다 */
    Created create(long adminId, NewBlock block);

    /** 푼다. 이미 풀렸으면 그대로 돌려준다 (기록은 남는다) */
    Block release(long adminId, long blockId);

    /** 이 막기와 겹친 끝나지 않은 예약 (풀렸거나 끝났으면 빈 목록) */
    List<Conflict> conflicts(long adminId, long blockId);

    /** 겹친 예약 중 아직 시작 전(BOOKED)인 것을 취소하고 대표자·참여자에게 알린다. 사용 중인 예약은 강제 퇴실로 따로 */
    Canceled cancelConflicts(long adminId, long blockId);

    record NewBlock(int roomId, Instant startAt, Instant endAt, BlockReason reason, String memo) {
    }

    record Block(long id, int roomId, ZonedDateTime startAt, ZonedDateTime endAt, BlockReason reason, String memo,
                 ZonedDateTime createdAt, ZonedDateTime releasedAt) {
    }

    /**
     * 막은 기간과 겹친 예약. 이름·학번 없이 번호·시간·인원만.
     *
     * @param cancelable 아직 시작 전이라 여기서 취소할 수 있는지. 사용 중(IN_USE)이거나 시작이 지난 예약은 false
     */
    record Conflict(long reservationId, int roomId, ZonedDateTime startAt, ZonedDateTime endAt, int people,
                    String status, boolean cancelable) {
    }

    record Created(Block block, List<Conflict> conflicts) {
    }

    record Canceled(int canceled, List<Long> reservationIds) {
    }
}
