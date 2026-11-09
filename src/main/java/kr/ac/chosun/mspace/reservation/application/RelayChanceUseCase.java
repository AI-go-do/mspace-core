package kr.ac.chosun.mspace.reservation.application;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/** 기다릴 칸 고르기: 그날 차 있는 예약마다 시작 30분 전까지 취소될 가능성 (11/9 지영) */
public interface RelayChanceUseCase {

    /** 가능성 높은 순. 내가 들어 있는 예약, 시작 30분 전이 지난 예약은 빠진다 */
    List<Chance> chances(long userId, LocalDate date);

    /**
     * @param percent 취소될 가능성(%)
     * @param reasons 이유 코드 (칸 기준만, 예약한 사람 정보 없음)
     * @param waiting 이 예약 시간 안의 칸에 이미 줄 선 사람 수
     */
    record Chance(int roomId, ZonedDateTime startAt, ZonedDateTime endAt, int percent, List<String> reasons,
                  int waiting) {
    }
}
