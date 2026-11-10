package kr.ac.chosun.mspace.appeal.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.appeal.application.WarningAppealUseCase.WarningItem;

/** 경고 철회 요청 저장소 (V29). 구현: JdbcWarningAppealAdapter */
public interface WarningAppealPort {

    /** 판단 대기 중인 요청을 잠근 결과 */
    record Pending(long appealId, long userId, long warningId, String reason, Long reservationId) {
    }

    /** @param appealableDays 요청 마감 = 경고 받은 시각 + 이 일수 */
    List<WarningItem> mine(long userId, Instant since, int appealableDays);

    Optional<WarningItem> one(long userId, long warningId, int appealableDays);

    /** 내 경고를 잠그고 받은 시각을 돌려준다. 남의 경고면 비어 있다 */
    Optional<Instant> lockMine(long userId, long warningId);

    /** @return 새로 넣었으면 true, 이미 요청이 있으면 false */
    boolean insertIfAbsent(long userId, long warningId, String text, List<String> evidence, Instant now);

    /** 판단 대기 중인 요청 id. 오래된 것부터 */
    List<Long> pending(int limit);

    /** 판단기에 넘길 사실. 대기 중이 아니면 비어 있다 */
    Optional<WarningReviewer.Case> loadCase(long appealId);

    /** 아직 대기 중이면 잠근다 (다른 판단이 먼저 끝냈으면 비어 있다) */
    Optional<Pending> lockPending(long appealId);

    void decide(long appealId, WarningReviewer.Decision decision, String answer, WarningReviewer.Review review, Instant now);

    void withdraw(long warningId, Instant now);

    /**
     * 그 경고가 센 정지가 아직이면 지금 끝낸다 (Q-24 ②). 위반 경고는 이용 정지(WARNINGS), 허위 신고 경고는 신고 정지.
     * "센 정지" = 그 경고가 정지를 건 경고 이하이고, 앞 정지 경계보다 뒤이고, 정지 시작 windowDays 안에 받은 경고
     *
     * @return 풀었으면 true
     */
    boolean liftSuspension(long userId, long warningId, String reason, int windowDays, Instant now);
}
