package kr.ac.chosun.mspace.ops.application;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * AI 수습사원 카드 점수 (11/13 지영 "실시간으로 맞힐 때마다 퍼센트랑 맞힘이 올라가게").
 * 점수 = 측정값(가짜 사례·실습실 기록으로 잰 값) + 실제 운영에서 맞음/틀림이 정해진 처리.
 * 실제 처리는 결과를 볼 수 있을 만큼 시간이 지나야 채점한다(그 전은 pending).
 */
public interface AiScoreUseCase {

    Scores scores(long adminId);

    /** right/total = base + live */
    record Item(String kind, int right, int total, Count base, Live live) {}

    record Count(int right, int total) {}

    /** pending = 아직 결과를 기다리는 실제 처리 수 */
    record Live(int right, int total, int pending) {}

    record Scores(ZonedDateTime at, List<Item> items) {}
}
