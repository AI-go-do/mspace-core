package kr.ac.chosun.mspace.reservation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.common.config.NoShowPenaltyPolicy;
import kr.ac.chosun.mspace.common.config.SensorPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class MyNoShowsService implements MyNoShowsUseCase {

    static final int MAX_DAYS = 90;

    private final MyNoShowsPort port;
    private final NoShowPenaltyPolicy penalty;
    private final SensorPolicy sensor;
    private final Clock clock;

    public MyNoShowsService(MyNoShowsPort port, NoShowPenaltyPolicy penalty, SensorPolicy sensor, Clock clock) {
        this.port = port;
        this.penalty = penalty;
        this.sensor = sensor;
        this.clock = clock;
    }

    @Override
    public Summary summary(long userId) {
        Instant now = clock.instant();
        List<Item> items = port.find(userId, now.minus(Duration.ofDays(penalty.windowDays())), now, penalty.windowDays());
        int noShows = (int) items.stream().filter(i -> i.counted() && "NO_SHOW".equals(i.kind())).count();
        int away = (int) items.stream().filter(i -> i.counted() && "AWAY_RELEASED".equals(i.kind())).count();
        return new Summary(noShows, away, penalty.count(), sensor.away().perNoShow(), penalty.windowDays());
    }

    @Override
    public List<Item> list(long userId, Integer days) {
        int d = days == null ? penalty.windowDays() : days;
        if (d < 1 || d > MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "days는 1–" + MAX_DAYS + " 사이여야 합니다");
        }
        Instant now = clock.instant();
        return port.find(userId, now.minus(Duration.ofDays(d)), now, penalty.windowDays());
    }
}
