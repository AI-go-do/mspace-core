package kr.ac.chosun.mspace.ops.application;

import java.time.Instant;
import java.util.Map;

/** 실제 처리를 채점하는 저장소 */
public interface AiScorePort {

    boolean isAdmin(long userId);

    /** 종류별 실제 처리 채점 (종류 이름 → 맞음·채점됨·기다림). 결과 볼 시간이 안 된 것은 pending */
    Map<String, Graded> grade(Instant now);

    record Graded(int right, int total, int pending) {
        public static final Graded NONE = new Graded(0, 0, 0);
    }
}
