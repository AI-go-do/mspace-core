package kr.ac.chosun.mspace.modelscore.application;

import java.time.Instant;
import java.util.List;

/**
 * 방 카메라 모델 시험 점수 (11/12 재웅·지영). 젯슨이 채점 결과 숫자를 올리고, 관리자 화면이 최신 값을 읽는다.
 * 숫자만 받는다(사진·문제집 없음).
 */
public interface ModelScoreUseCase {

    int LIST_LIMIT = 20;

    /** 지금은 정리 확인 모델 하나 */
    enum Kind { TIDY }

    /**
     * @param accuracy      깨끗함/남음 정확도 0~1 (화면에 보이는 값)
     * @param accuracy3     세 가지 정확도 0~1, 없으면 null
     * @param serious       심각 오류 수, 없으면 null
     * @param medianSeconds 사진 한 장 판단 시간 중간값(초)
     * @param deviceId      보낸 장치 이름, 없으면 null
     */
    record Score(String eventId, Kind kind, String model, float accuracy, Float accuracy3, int cases,
                 Integer serious, float medianSeconds, Instant measuredAt, int roomId, String deviceId) {
    }

    /** @return 같은 eventId가 이미 있으면 true(처음 것을 그대로 둔다) */
    boolean submit(Score score);

    /** 관리자만(403 ADMIN_ONLY). 채점 시각 최근 순 {@value LIST_LIMIT}개 */
    List<Score> recent(long adminId, Kind kind);
}
