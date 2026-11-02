package kr.ac.chosun.mspace.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "지금"은 항상 Clock에서 얻는다 (팀 컨벤션). 테스트에서는 고정된 Clock으로 바꿔
 * "7일째 23:00", "지난 칸" 같은 경계를 기다리지 않고 확인한다.
 */
@Configuration
public class TimeConfig {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean
    Clock clock() {
        return Clock.system(SEOUL);
    }
}
