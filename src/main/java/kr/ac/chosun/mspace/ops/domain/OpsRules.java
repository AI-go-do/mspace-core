package kr.ac.chosun.mspace.ops.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import kr.ac.chosun.mspace.common.config.OpsPolicy;

/**
 * AI 자동 처리 숫자 규칙 5개 (11/12 지영). DB·시계를 모르는 순수 함수라 서비스와 정확도 측정(OpsRulesEval)이 같은 코드를 쓴다.
 * 값 목록은 모두 최신이 앞.
 */
public final class OpsRules {

    private OpsRules() {
    }

    /** 센서 값 하나 (CO2 ppm 또는 온도 ℃) */
    public record Point(Instant at, double value) {
    }

    /** 예약 하나. end = 실제로 끝난 시각(먼저 퇴실했으면 퇴실 시각) */
    public record Span(Instant start, Instant end) {
    }

    /** 미리 환기: 지금은 기준 이하인데 오르는 속도대로면 lookahead 안에 기준을 넘는다 */
    public static boolean ventSoon(List<Point> co2, Instant now, OpsPolicy.VentSoon p) {
        List<Point> pts = co2.stream().filter(x -> !x.at().isBefore(now.minus(p.window())) && !x.at().isAfter(now))
                .toList();
        if (pts.size() < p.minPoints()) {
            return false;
        }
        double latest = p.smooth() && pts.size() >= 3 ? median(pts.subList(0, 3)) : pts.getFirst().value();
        if (pts.getFirst().value() > p.thresholdPpm() || latest > p.thresholdPpm()) {
            return false;
        }
        double slope = p.fit() ? fitSlope(pts) : twoPointSlope(pts);
        if (slope <= 0) {
            return false;
        }
        // 1000 바로 아래(nearPpm 이상)에서 오르는 중이면 길게(nearLookahead) 본다. 큰 방은 천천히 올라서 8분 예측으로는 늦다 (실습실 기록 11/12)
        if (p.nearPpm() > 0 && latest >= p.nearPpm()) {
            return latest + slope * p.nearLookahead().toSeconds() >= p.thresholdPpm();
        }
        // 그 아래에서는 빨리 오를 때만 짧게(lookahead) 예측한다. 천천히 1000 근처를 오르내리는 방의 헛알림을 막는다
        if (latest < p.floorPpm() || slope * 60 < p.fastPerMin()) {
            return false;
        }
        return latest + slope * p.lookahead().toSeconds() >= p.thresholdPpm();
    }

    /**
     * 예약 전 더움: 믿을 만한(fresh) 최근 값 median개의 가운데 값(trend면 start 시각 예측값)이 기준 이상
     *
     * @param start 예약 시작 시각. 예측은 지금(sinceLast면 마지막 측정 시각)부터 start까지
     */
    public static boolean roomHot(List<Point> temps, Instant now, Instant start, OpsPolicy.Hot p) {
        List<Point> fresh = temps.stream()
                .filter(x -> !x.at().isBefore(now.minus(p.fresh())) && !x.at().isAfter(now)).toList();
        double[] v = fresh.stream().limit(Math.max(1, p.median())).mapToDouble(Point::value).sorted().toArray();
        if (v.length == 0) {
            return false;
        }
        double at = v[v.length / 2];
        if (p.trend() && fresh.size() >= (p.robust() ? 6 : 3)) {
            long ahead = Duration.between(p.sinceLast() ? fresh.getFirst().at() : now, start).toSeconds();
            at += (p.robust() ? robustSlope(fresh) : fitSlope(fresh)) * ahead;
        }
        return at >= p.thresholdC();
    }

    /**
     * 센서 고장: 그 방 신호가 after 넘게 안 옴. 한 번도 안 온 방(lastReceivedAt null)은 설치 전이라 안 봄
     *
     * @param anotherRoomAlive 다른 방 중 하나라도 after 안에 신호가 왔는지
     */
    public static boolean sensorDown(Instant lastReceivedAt, Instant now, boolean anotherRoomAlive,
                                     OpsPolicy.SensorDown p) {
        if (lastReceivedAt == null || now.isBefore(lastReceivedAt.plus(p.after()))) {
            return false;
        }
        return !p.skipWhenAllDown() || anotherRoomAlive;
    }

    /**
     * 시간 넘김: 예약이 endAt에 자동 퇴실됐는데 after 지나도 "있음"
     *
     * @param occupiedAtEnd 끝난 시각에 "있음"이었는지 (sensor_event 재실 기록)
     * @param occupiedSince 지금 "있음"이 시작된 시각 (room_presence.changed_at)
     * @param nextStart     같은 방 다음 예약 시작. 없으면 null
     */
    public static boolean overtime(Instant endAt, boolean occupiedAtEnd, boolean occupied, Instant occupiedSince,
                                   Instant now, Instant nextStart, OpsPolicy.Overtime p) {
        if (!occupied || now.isBefore(endAt.plus(p.after())) || !now.isBefore(endAt.plus(p.within()))) {
            return false;
        }
        if (p.atEnd() ? !occupiedAtEnd : p.continuous() && occupiedSince.isAfter(endAt)) {
            return false;
        }
        if (nextStart == null || p.nextGrace().isZero()) {
            return true;
        }
        // 즉시 시작 예약은 30분 칸이 아니라 누른 시각에 시작해서 끝 + nextGrace와 같을 수 있다. 같으면 다음 팀으로 본다
        return nextStart.isAfter(endAt.plus(p.nextGrace()));
    }

    /** 예약 없는 사용: after 넘게 "있음"인데 [시작 - earlyGrace, 끝 + lateGrace)에 드는 예약이 없다 */
    public static boolean unbooked(boolean occupied, Instant occupiedSince, Instant now, List<Span> bookings,
                                   OpsPolicy.Unbooked p) {
        if (!occupied || now.isBefore(occupiedSince.plus(p.after()))) {
            return false;
        }
        return bookings.stream().noneMatch(b -> !now.isBefore(b.start().minus(p.earlyGrace()))
                && now.isBefore(b.end().plus(p.lateGrace())));
    }

    private static double twoPointSlope(List<Point> pts) {
        Point a = pts.getLast();
        Point b = pts.getFirst();
        long dt = Duration.between(a.at(), b.at()).toSeconds();
        return dt <= 0 ? 0 : (b.value() - a.value()) / dt;
    }

    /** 최근 3개와 가장 오래된 3개의 가운데 값·가운데 시각으로 잰 기울기 (초당). 6개 이상일 때만 */
    private static double robustSlope(List<Point> pts) {
        List<Point> recent = pts.subList(0, 3);
        List<Point> old = pts.subList(pts.size() - 3, pts.size());
        long dt = Duration.between(old.get(1).at(), recent.get(1).at()).toSeconds();
        return dt <= 0 ? 0 : (median(recent) - median(old)) / dt;
    }

    private static double median(List<Point> pts) {
        return pts.stream().mapToDouble(Point::value).sorted().toArray()[pts.size() / 2];
    }

    /** 최소제곱 기울기 (초당) */
    private static double fitSlope(List<Point> pts) {
        Instant t0 = pts.getLast().at();
        double[] x = pts.stream().mapToDouble(q -> Duration.between(t0, q.at()).toSeconds()).toArray();
        double mx = Arrays.stream(x).average().orElse(0);
        double my = pts.stream().mapToDouble(Point::value).average().orElse(0);
        double num = 0;
        double den = 0;
        for (int i = 0; i < x.length; i++) {
            num += (x[i] - mx) * (pts.get(i).value() - my);
            den += (x[i] - mx) * (x[i] - mx);
        }
        return den == 0 ? 0 : num / den;
    }
}
