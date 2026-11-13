package kr.ac.chosun.mspace.ops.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
class AiScoreService implements AiScoreUseCase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 화면 카드 순서 */
    static final List<String> KINDS = List.of("VENT_SOON", "SENSOR_DOWN", "ROOM_HOT", "OVERTIME", "UNBOOKED",
            "NO_SHOW_EXCUSE", "REPORT_JUDGE", "APPEAL_REVIEW");

    private final AiScorePort port;
    private final AiScoreProperties props;
    private final Clock clock;

    AiScoreService(AiScorePort port, AiScoreProperties props, Clock clock) {
        this.port = port;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public Scores scores(long adminId) {
        if (!port.isAdmin(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        Instant now = clock.instant();
        Map<String, AiScorePort.Graded> graded = port.grade(now);
        Map<String, AiScoreProperties.Base> bases = props.base() == null ? Map.of() : props.base();
        List<Item> items = new ArrayList<>();
        for (String kind : KINDS) {
            AiScoreProperties.Base b = bases.getOrDefault(kind, new AiScoreProperties.Base(0, 0));
            AiScorePort.Graded g = graded.getOrDefault(kind, AiScorePort.Graded.NONE);
            items.add(new Item(kind, b.right() + g.right(), b.total() + g.total(),
                    new Count(b.right(), b.total()), new Live(g.right(), g.total(), g.pending())));
        }
        return new Scores(now.atZone(SEOUL), items);
    }
}
