package kr.ac.chosun.mspace.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 자동 처리 숫자 규칙 (11/12 지영 "관리자 카드 다 AI가 처리하게"). 관리자는 보기만 한다.
 * 숫자는 가짜 사례 측정(OpsRulesEval, README "실험 기록: 자동 처리 규칙")으로 고른 값이다.
 *
 * @param interval   이만큼마다 본다 (1분)
 * @param ventSoon   미리 환기: CO2가 아직 기준 아래인데 오르는 속도대로면 곧 넘을 때 방 사람에게 알림
 * @param hot        예약 전 더움: 시작 전 방이 더우면 대표자에게 알림
 * @param sensorDown 센서 고장: 방 센서 신호가 끊기면 관리자 기록
 * @param overtime   시간 넘김: 자동 퇴실 뒤에도 방에 사람이 있으면 그 팀에게 알림
 * @param unbooked   예약 없는 사용: 예약이 없는데 사람이 있으면 관리자 기록
 */
@ConfigurationProperties("mspace.ops")
public record OpsPolicy(Duration interval, VentSoon ventSoon, Hot hot, SensorDown sensorDown, Overtime overtime, Unbooked unbooked) {

    /**
     * @param thresholdPpm 환기 기준 (센서 환기 알림과 같은 1000)
     * @param lookahead    이만큼 뒤에 기준을 넘을 것 같으면 미리 알림
     * @param window       최근 이만큼의 값으로 오르는 속도를 잰다
     * @param minPoints    window 안 값이 이만큼은 있어야 본다
     * @param floorPpm     지금 값이 이 아래면 보지 않는다 (막 들어와 빨리 오르는 때 헛알림 막기)
     * @param fit          true: 모든 값으로 기울기(최소제곱), false: 첫 값과 마지막 값 두 개로
     * @param smooth       true: 지금 값을 최근 3개의 가운데 값으로 (센서 앞에서 숨 쉬어 튄 값 하나에 안 흔들리게)
     */
    public record VentSoon(int thresholdPpm, Duration lookahead, Duration window, int minPoints, int floorPpm,
                           boolean fit, boolean smooth) {
    }

    /**
     * @param thresholdC 이 온도 이상이면 덥다
     * @param before     시작 이만큼 전에 본다
     * @param median     최근 값 이만큼의 가운데 값으로 본다 (튀는 값 하나에 안 흔들리게)
     * @param fresh      이보다 오래된 값은 안 믿는다
     * @param trend      true: 믿을 만한 값들의 기울기로 시작 시각 온도를 예측해서 본다 (에어컨으로 식는 중이면 안 알림)
     * @param robust     true: 기울기를 최소제곱 대신 "최근 3개 가운데 값 − 오래된 3개 가운데 값"으로 (튀는 값 하나에 안 흔들리게)
     * @param sinceLast  true: 마지막 값이 오래됐으면 그 시각부터 시작 시각까지를 예측 (아니면 지금부터 before만큼)
     */
    public record Hot(double thresholdC, Duration before, int median, Duration fresh, boolean trend, boolean robust,
                      boolean sinceLast) {
    }

    /**
     * @param after          신호가 이만큼 안 오면 고장으로 본다
     * @param skipWhenAllDown 모든 방이 같이 끊기면 센서가 아니라 네트워크·서버 문제라 센서 고장으로 안 본다
     */
    public record SensorDown(Duration after, boolean skipWhenAllDown) {
    }

    /**
     * @param after      끝난 뒤 이만큼 지나도 사람이 있으면 알림
     * @param within     끝난 뒤 이만큼까지만 본다
     * @param continuous true: 끝나기 전부터 계속 "있음"일 때만 (끝난 뒤 새로 들어온 사람은 그 팀이 아님)
     * @param atEnd      true: continuous 대신 "끝난 시각에 있음 + 지금 있음"으로 본다 (사이에 잠깐 "없음"은 봐줌)
     * @param nextGrace  다음 예약이 끝난 시각부터 이만큼 안에 시작하면 안 알림 (다음 팀이 먼저 들어와 있을 수 있음). 0이면 늘 알림
     */
    public record Overtime(Duration after, Duration within, boolean continuous, boolean atEnd, Duration nextGrace) {
    }

    /**
     * @param after      "있음"이 이만큼 이어져야 기록
     * @param earlyGrace 예약 시작 이만큼 전부터는 그 팀으로 본다 (입실은 10분 전부터)
     * @param lateGrace  예약이 끝난 뒤 이만큼까지는 그 팀으로 본다 (시간 넘김 규칙이 맡음)
     */
    public record Unbooked(Duration after, Duration earlyGrace, Duration lateGrace) {
    }
}
