package kr.ac.chosun.mspace.admin.application;

import java.time.Instant;
import java.util.List;

/** 관리자 경고·정지 목록 (FR-WEB-101 관리자 화면 탭). 구현: {@link PenaltyListService} */
public interface PenaltyListUseCase {

    int DEFAULT_DAYS = 30;
    int MAX_DAYS = 365;

    /**
     * 최근 days일 안에 받은 경고와, 그 사이에 끝났거나 아직 진행 중인 정지. 최근 것부터.
     * 관리자 화면이라 학번·이름을 그대로 보여 준다.
     */
    Penalties list(long adminId, Integer days);

    record Penalties(int days, List<Warning> warnings, List<Suspension> suspensions) {
    }

    /** @param reason VIOLATION(사실 판정, 이용한 방) 또는 FALSE_REPORT(허위 신고) */
    record Warning(long id, Member member, String reason, long reportId, Long reservationId, Instant createdAt) {
    }

    /**
     * @param scope  USE(예약·참여 정지) 또는 REPORT(신고만 정지)
     * @param reason NO_SHOW(노쇼 누적), WARNINGS(위반 경고 누적), FALSE_REPORT(허위 신고 경고 누적)
     */
    record Suspension(String scope, long id, Member member, String reason, Instant startsAt, Instant endsAt,
                      boolean active) {
    }

    record Member(long id, String studentId, String name) {
    }
}
