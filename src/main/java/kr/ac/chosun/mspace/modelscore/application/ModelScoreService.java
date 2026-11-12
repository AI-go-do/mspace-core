package kr.ac.chosun.mspace.modelscore.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/** 같은 eventId는 DB 유니크(ON CONFLICT DO NOTHING)로 한 줄만. 동시에 두 번 와도 잠금 없이 한 줄 */
@Service
class ModelScoreService implements ModelScoreUseCase {

    /** 젯슨 시계가 core보다 조금 빠를 수 있어 둔 여유 */
    static final Duration MAX_CLOCK_AHEAD = Duration.ofMinutes(10);

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
        Instant now = clock.instant();
        // 장치 시계가 틀려 먼 미래 시각이 오면 그 점수가 오래 "지금 정확도"로 맨 앞에 남는다
        if (score.measuredAt().isAfter(now.plus(MAX_CLOCK_AHEAD))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "measuredAt이 지금보다 10분 넘게 미래입니다");
        }
        return !port.insert(score, now);
    }

    @Override
    public List<Score> recent(long adminId, Kind kind) {
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        return port.recent(kind, LIST_LIMIT);
    }
}
