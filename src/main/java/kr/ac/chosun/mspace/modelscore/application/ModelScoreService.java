package kr.ac.chosun.mspace.modelscore.application;

import java.time.Clock;
import java.util.List;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/** 같은 eventId는 DB 유니크(ON CONFLICT DO NOTHING)로 한 줄만. 동시에 두 번 와도 잠금 없이 한 줄 */
@Service
class ModelScoreService implements ModelScoreUseCase {

    private final ModelScorePort port;
    private final Clock clock;

    ModelScoreService(ModelScorePort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    @Override
    public boolean submit(Score score) {
        if (!port.roomExists(score.roomId())) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
        return !port.insert(score, clock.instant());
    }

    @Override
    public List<Score> recent(long adminId, Kind kind) {
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        return port.recent(kind, LIST_LIMIT);
    }
}
