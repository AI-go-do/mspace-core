package kr.ac.chosun.mspace.ops.application;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카드마다 시작 점수(측정값). 키는 카드 종류(VENT_SOON, ROOM_HOT, SENSOR_DOWN, OVERTIME, UNBOOKED,
 * REPORT_JUDGE, APPEAL_REVIEW, NO_SHOW_EXCUSE). 측정을 다시 하면 이 숫자만 바꾼다
 */
@ConfigurationProperties("mspace.ai-score")
public record AiScoreProperties(Map<String, Base> base) {

    public record Base(int right, int total) {}
}
