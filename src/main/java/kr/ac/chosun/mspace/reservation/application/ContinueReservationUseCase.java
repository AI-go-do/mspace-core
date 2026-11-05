package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import kr.ac.chosun.mspace.reservation.application.CreateReservationUseCase.Created;
import kr.ac.chosun.mspace.reservation.domain.CreatedVia;

/**
 * 연장하기 입력 포트 (FR-RSV-105). 구현: {@link ContinueReservationService}
 */
public interface ContinueReservationUseCase {

    /**
     * 사용 중인 예약을 퇴실하고, 지금부터 endAt까지 새 예약을 바로 사용 중(IN_USE)으로 만든다. 둘은 한 트랜잭션이라
     * 새 예약이 실패하면(다음 칸을 남이 잡음 등) 퇴실도 되돌려져 지금 예약이 그대로 남는다.
     *
     * @param reservationId 지금 쓰는 예약
     * @param userId        로그인 사용자 (대표자여야 한다)
     * @return 새 예약과 새 입실 번호. 같은 멱등 키 재요청이면 replayed = true
     */
    Created continueUse(long reservationId, long userId, Command command);

    /**
     * @param endAt          새 종료 시각. 지금 종료 + 30분 단위, 최대 2시간
     * @param idempotencyKey 멱등 키. 없으면 null
     */
    record Command(Instant endAt, String idempotencyKey, CreatedVia createdVia) {
    }
}
