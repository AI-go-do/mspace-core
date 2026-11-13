package kr.ac.chosun.mspace.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.common.config.OpsPolicy;
import kr.ac.chosun.mspace.ops.domain.OpsRules;
import kr.ac.chosun.mspace.ops.domain.OpsRules.Point;
import kr.ac.chosun.mspace.ops.domain.OpsRules.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AI 자동 처리 숫자 규칙 (application.yml에 고른 판의 값) */
class OpsRulesTest {

    static final Instant NOW = Instant.parse("2030-03-05T01:00:00Z");
    static final OpsPolicy.VentSoon VENT = new OpsPolicy.VentSoon(1000, Duration.ofMinutes(8), Duration.ofMinutes(5), 2,
            800, true, true, 0, null, 0);
    /** 지금 쓰는 11판 (application.yml과 같음) */
    static final OpsPolicy.VentSoon VENT11 = new OpsPolicy.VentSoon(1000, Duration.ofMinutes(8), Duration.ofMinutes(5), 2,
            800, true, true, 960, Duration.ofMinutes(30), 20);
    static final OpsPolicy.Hot HOT = new OpsPolicy.Hot(28, Duration.ofMinutes(10), 3, Duration.ofMinutes(60), true,
            true, true);
    static final OpsPolicy.SensorDown DOWN = new OpsPolicy.SensorDown(Duration.ofMinutes(5), true);
    static final OpsPolicy.Overtime OVER = new OpsPolicy.Overtime(Duration.ofMinutes(5), Duration.ofMinutes(15), true,
            false, Duration.ofMinutes(10));
    static final OpsPolicy.Unbooked UNB = new OpsPolicy.Unbooked(Duration.ofMinutes(10), Duration.ofMinutes(10),
            Duration.ofMinutes(30));

    static List<Point> series(double... newestFirst) {
        return java.util.stream.IntStream.range(0, newestFirst.length)
                .mapToObj(i -> new Point(NOW.minusSeconds(60L * i), newestFirst[i])).toList();
    }

    static Instant min(int m) {
        return NOW.plusSeconds(60L * m);
    }

    @Test
    @DisplayName("미리 환기: 1분에 30ppm씩 올라 8분 안에 1000을 넘으면 알리고, 천천히 오르거나 800 아래·1000 위면 안 알린다")
    void ventSoon() {
        assertThat(OpsRules.ventSoon(series(910, 880, 850, 820, 790, 760), NOW, VENT)).isTrue();
        assertThat(OpsRules.ventSoon(series(910, 905, 900, 895, 890, 885), NOW, VENT)).isFalse();
        assertThat(OpsRules.ventSoon(series(790, 700, 610, 520), NOW, VENT)).isFalse();
        assertThat(OpsRules.ventSoon(series(1010, 980, 950), NOW, VENT)).isFalse();
        assertThat(OpsRules.ventSoon(series(1060, 900, 870, 840), NOW, VENT)).isFalse();   // 마지막 값이 이미 넘음: 환기 알림이 맡음
        assertThat(OpsRules.ventSoon(series(960), NOW, VENT)).isFalse();   // 값 1개
    }

    @Test
    @DisplayName("미리 환기 11판: 960 이상에서 천천히 오르면 30분 앞까지 보고, 그 아래에서는 1분에 20 넘게 빨리 오를 때만 알린다")
    void ventSoonNear() {
        // 970 근처에서 1분에 약 3씩 오름: 8분 예측으론 994라 6판은 안 알리지만 30분 예측이면 넘는다
        assertThat(OpsRules.ventSoon(series(975, 970, 968, 965, 962, 960), NOW, VENT)).isFalse();
        assertThat(OpsRules.ventSoon(series(975, 970, 968, 965, 962, 960), NOW, VENT11)).isTrue();
        // 960 이상이어도 내려가는 중이면 안 알린다
        assertThat(OpsRules.ventSoon(series(962, 968, 972, 975, 980, 985), NOW, VENT11)).isFalse();
        // 960 아래에서 1분에 10씩: 6판은 알리지만 11판은 1000 근처 오르내림으로 보고 안 알린다
        assertThat(OpsRules.ventSoon(series(940, 930, 920, 910, 900, 890), NOW, VENT)).isTrue();
        assertThat(OpsRules.ventSoon(series(940, 930, 920, 910, 900, 890), NOW, VENT11)).isFalse();
        // 960 아래에서 1분에 30씩 빨리 오르면 알린다
        assertThat(OpsRules.ventSoon(series(910, 880, 850, 820, 790, 760), NOW, VENT11)).isTrue();
        // 딱 1분에 20이면 "넘게"가 아니라 안 알린다 (8분 예측은 1080이어도)
        assertThat(OpsRules.ventSoon(series(940, 920, 900, 880, 860, 840), NOW, VENT11)).isFalse();
    }

    @Test
    @DisplayName("예약 전 더움: 29도로 그대로면 알리고, 에어컨으로 식는 중이거나 값이 60분보다 오래됐으면 안 알린다")
    void roomHot() {
        assertThat(OpsRules.roomHot(series(29, 29.1, 28.9, 29, 29.2, 29, 28.9), NOW, NOW.plus(HOT.before()), HOT)).isTrue();
        assertThat(OpsRules.roomHot(series(28.4, 28.9, 29.4, 29.9, 30.4, 30.9, 31.4), NOW, NOW.plus(HOT.before()), HOT)).isFalse();
        assertThat(OpsRules.roomHot(series(29, 33, 29.1, 28.9, 29, 29.1), NOW, NOW.plus(HOT.before()), HOT)).isTrue();   // 튀는 값 하나
        assertThat(OpsRules.roomHot(List.of(new Point(NOW.minusSeconds(61 * 60), 30)), NOW, NOW.plus(HOT.before()), HOT)).isFalse();
    }

    @Test
    @DisplayName("예약 전 더움: 분당 0.1도씩 오르는 27.5도 방은 10분 뒤 시작이면 알리고, 1분 뒤 시작이면 안 알린다 (예측은 실제 시작 시각까지)")
    void roomHotUsesStart() {
        List<Point> rising = series(27.5, 27.4, 27.3, 27.2, 27.1, 27.0, 26.9);
        assertThat(OpsRules.roomHot(rising, NOW, min(10), HOT)).isTrue();
        assertThat(OpsRules.roomHot(rising, NOW, min(1), HOT)).isFalse();
    }

    @Test
    @DisplayName("센서 고장: 5분 넘게 안 오고 다른 방은 살아 있을 때만. 모든 방이 끊기면 네트워크 문제로 본다")
    void sensorDown() {
        assertThat(OpsRules.sensorDown(min(-5), NOW, true, DOWN)).isTrue();
        assertThat(OpsRules.sensorDown(min(-4), NOW, true, DOWN)).isFalse();
        assertThat(OpsRules.sensorDown(min(-10), NOW, false, DOWN)).isFalse();
        assertThat(OpsRules.sensorDown(null, NOW, true, DOWN)).isFalse();
    }

    @Test
    @DisplayName("시간 넘김: 끝나기 전부터 계속 있고 5–15분 사이면 알린다. 끝난 뒤 들어왔거나 다음 예약이 바로면 안 알린다")
    void overtime() {
        Instant end = min(-6);
        assertThat(OpsRules.overtime(end, true, true, min(-30), NOW, null, OVER)).isTrue();
        assertThat(OpsRules.overtime(end, true, true, min(-30), NOW, min(30), OVER)).isTrue();
        assertThat(OpsRules.overtime(end, false, true, min(-3), NOW, null, OVER)).isFalse();
        assertThat(OpsRules.overtime(end, true, true, min(-30), NOW, end, OVER)).isFalse();
        // 즉시 시작은 아무 분에나 시작: 끝 + 10분이면 다음 팀(안 알림), 그 뒤면 알림. 0분이면 늘 알림
        assertThat(OpsRules.overtime(end, true, true, min(-30), NOW, end.plusSeconds(600), OVER)).isFalse();
        assertThat(OpsRules.overtime(end, true, true, min(-30), NOW, end.plusSeconds(660), OVER)).isTrue();
        var noGrace = new OpsPolicy.Overtime(OVER.after(), OVER.within(), true, false, Duration.ZERO);
        assertThat(OpsRules.overtime(end, true, true, min(-30), NOW, end, noGrace)).isTrue();
        assertThat(OpsRules.overtime(min(-4), true, true, min(-30), NOW, null, OVER)).isFalse();
        assertThat(OpsRules.overtime(min(-16), true, true, min(-30), NOW, null, OVER)).isFalse();
    }

    @Test
    @DisplayName("예약 없는 사용: 10분 넘게 있고 예약 10분 전·끝난 뒤 30분 안이 아니면 기록한다")
    void unbooked() {
        assertThat(OpsRules.unbooked(true, min(-10), NOW, List.of(), UNB)).isTrue();
        assertThat(OpsRules.unbooked(true, min(-9), NOW, List.of(), UNB)).isFalse();
        assertThat(OpsRules.unbooked(true, min(-20), NOW, List.of(new Span(min(10), min(70))), UNB)).isFalse();
        assertThat(OpsRules.unbooked(true, min(-20), NOW, List.of(new Span(min(-90), min(-29))), UNB)).isFalse();
        assertThat(OpsRules.unbooked(true, min(-20), NOW, List.of(new Span(min(-90), min(-31))), UNB)).isTrue();
        assertThat(OpsRules.unbooked(false, min(-20), NOW, List.of(), UNB)).isFalse();
    }
}
