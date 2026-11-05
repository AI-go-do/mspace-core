package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.Map;
import kr.ac.chosun.mspace.reservation.domain.CreatedVia;

/** 예약 건수 세기용 DB 일. 구현은 adapter/out/persistence */
public interface ReservationStatsPort {

    /**
     * created_at이 [from, to) 안인 예약을 created_via별로 센다. 0건인 종류는 빠질 수 있다.
     *
     * @param from null이면 아래 끝 없음
     * @param to   null이면 위 끝 없음
     */
    Map<CreatedVia, Long> countByCreatedVia(Instant from, Instant to);

    /**
     * 이 회원이 관리자(users.role = ADMIN)인지. 없는 회원이면 false.
     * TODO(관리자 기능이 늘면): member 모듈 application의 공개 창구로 옮긴다 (지금은 쓰는 곳이 여기 하나라 예약 어댑터가 직접 읽음)
     */
    boolean isAdmin(long userId);
}
