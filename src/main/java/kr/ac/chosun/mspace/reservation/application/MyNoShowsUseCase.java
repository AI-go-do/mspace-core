package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.List;

/**
 * 내 노쇼 기록 (FR-RSV-106, 11/12 지영 "내 경고 기록에 노쇼 기록도 보이게"). 구현: {@link MyNoShowsService}.
 * 정지를 거는 셈(JdbcReservationSweepAdapter.suspendRepeatedNoShows)과 같은 기준으로 센다:
 * 대표자 기록만, 장치 오류로 무른 노쇼 제외, 지난 노쇼 정지가 끝난 뒤의 것만, 30일 안
 */
public interface MyNoShowsUseCase {

    /** 다음 정지 셈에 들어가는 횟수 */
    Summary summary(long userId);

    /**
     * 최근 days일 기록, 최근 것부터. 셈에서 빠진 것(무름·지난 정지 전·30일 밖)도 counted=false로 준다
     *
     * @param days 비면 30, 1–90 밖이면 400 INVALID_REQUEST
     */
    List<Item> list(long userId, Integer days);

    /**
     * @param noShows       셈에 들어가는 노쇼 수
     * @param awayReleases  셈에 들어가는 자리 비움 자동 퇴실 수 (awayPerNoShow번 = 노쇼 1번)
     * @param limit         노쇼 이 횟수면 정지 (2)
     * @param awayPerNoShow 자리 비움 몇 번이 노쇼 1번인가 (3)
     * @param windowDays    세는 기간 (30)
     */
    record Summary(int noShows, int awayReleases, int limit, int awayPerNoShow, int windowDays) {
    }

    /**
     * @param kind       NO_SHOW 또는 AWAY_RELEASED
     * @param happenedAt 노쇼는 입실 마감(끝이 더 이르면 끝), 자리 비움은 자동 퇴실 시각
     * @param counted    다음 정지 셈에 들어가는지
     * @param excusedAt  장치 오류로 무른 시각, 아니면 null
     */
    record Item(long reservationId, int roomId, String roomName, Instant startAt, Instant endAt, String kind,
                Instant happenedAt, boolean counted, Instant excusedAt) {
    }
}
