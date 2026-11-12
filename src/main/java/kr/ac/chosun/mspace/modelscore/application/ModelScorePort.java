package kr.ac.chosun.mspace.modelscore.application;

import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.modelscore.application.ModelScoreUseCase.Kind;
import kr.ac.chosun.mspace.modelscore.application.ModelScoreUseCase.Score;

public interface ModelScorePort {

    boolean roomExists(int roomId);

    /** @return 새로 넣었으면 true, 같은 eventId가 있어 넣지 않았으면 false */
    boolean insert(Score score, Instant receivedAt);

    List<Score> recent(Kind kind, int limit);

    boolean isAdmin(long userId);
}
