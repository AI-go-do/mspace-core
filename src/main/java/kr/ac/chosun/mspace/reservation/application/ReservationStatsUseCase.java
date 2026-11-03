package kr.ac.chosun.mspace.reservation.application;

import java.time.LocalDate;

/**
 * 예약 건수 (WEB/AGENT 따로). 구현: {@link ReservationStatsService}
 * 숫자만 돌려주고, 누가·어느 방·언제는 돌려주지 않는다.
 */
public interface ReservationStatsUseCase {

    /**
     * 예약을 만든 날(created_at, 서울 날짜) 기준으로 센다. 취소·노쇼된 예약도 "만든 건수"에 들어간다.
     *
     * @param userId 부른 회원. 관리자가 아니면 403(ADMIN_ONLY)
     * @param from   이 날부터 (포함). null이면 처음부터
     * @param to     이 날까지 (포함). null이면 지금까지
     */
    Counts count(long userId, LocalDate from, LocalDate to);

    record Counts(LocalDate from, LocalDate to, long total, long web, long agent) {
    }
}
