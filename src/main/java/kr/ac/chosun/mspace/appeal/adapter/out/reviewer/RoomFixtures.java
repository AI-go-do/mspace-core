package kr.ac.chosun.mspace.appeal.adapter.out.reviewer;

import java.util.Locale;
import java.util.Set;

/**
 * 방에 원래 있는 비품 이름 (방 카메라 모델이 내는 영어 이름 기준). 다시 확인 때 이것만 남았으면 학생이 두고 간 물건이 아니다.
 * 11/11 정확도 측정: 비품(의자·책상·모니터·쓰레기통)을 두고 간 물건으로 봐서 경고를 유지한 오류가 가장 많았다.
 * TODO 실제 M-SPACE 방 비품 목록과 수연 카메라 모델 이름으로 확인 필요 (지금은 스터디룸 일반 비품으로 가정)
 */
final class RoomFixtures {

    static final Set<String> NAMES = Set.of("chair", "desk", "table", "dining table", "whiteboard", "monitor", "tv",
            "trash can", "clock", "air conditioner", "couch", "bench");

    private RoomFixtures() {
    }

    static boolean isFixture(String name) {
        return name != null && NAMES.contains(name.strip().toLowerCase(Locale.ROOT));
    }
}
