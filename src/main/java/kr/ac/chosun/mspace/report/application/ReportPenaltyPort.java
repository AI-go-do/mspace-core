package kr.ac.chosun.mspace.report.application;

import java.time.Instant;
import java.util.Optional;

/** 신고 판정 뒤 벌칙 저장소 (FR-RPT-103·104, V16). 구현: JdbcReportPenaltyAdapter */
public interface ReportPenaltyPort {

    enum WarningReason { VIOLATION, FALSE_REPORT }

    /** 경고 받을 사람과 그 예약 */
    record Violator(long userId, long reservationId) {
    }

    /** 신고한 회원 */
    long reporterId(long reportId);

    /**
     * 신고 직전에 그 방을 쓴 예약의 대표자 (11/6 지영 결정: 대표자만).
     * 신고 시각 전에 입실한 예약 중 가장 늦게 입실한 것. 신고자 자신의 근거 예약은 뺀다
     */
    Optional<Violator> findViolator(long reportId);

    /** 같은 사람의 경고·정지를 한 줄로 세운다 (트랜잭션이 끝나면 풀림). 두 판정이 동시에 3번째를 세다 놓치지 않게 */
    void lockUser(long userId);

    void warn(long userId, WarningReason reason, long reportId, Long reservationId, Instant now);

    /** windowDays 안의 경고 수. 같은 종류 정지가 시작된 뒤의 경고만 센다 (정지가 끝나면 처음부터) */
    int countWarnings(long userId, WarningReason reason, Instant now, int windowDays);

    /** VIOLATION → 이용 정지, FALSE_REPORT → 신고 정지. 이미 기간이 겹치는 정지가 있으면 넣지 않는다 */
    boolean suspend(long userId, WarningReason reason, Instant from, Instant until);

    /** 신고 정지 중이면 끝나는 시각 */
    Optional<Instant> reportSuspendedUntil(long userId, Instant now);
}
