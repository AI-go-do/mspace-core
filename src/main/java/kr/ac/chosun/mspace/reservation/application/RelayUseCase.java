package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 빈방 릴레이 (Q-18, 11/9 지영).
 * <ol>
 *   <li>차 있는 30분 칸에 기다리기를 건다. 한 사람 동시에 3개까지, 칸마다 줄 선 순서대로</li>
 *   <li>그 칸을 덮는 예약이 취소되면 줄 첫 사람에게 놓인 구간을 넘긴다. 5분 동안은 그 사람만 예약할 수 있다</li>
 *   <li>받기는 보통 예약(POST /api/reservations)에 relayOfferId를 붙여서. 규칙(인원·친구·정지·대표자 1건)은 그대로</li>
 *   <li>"넘길게요"나 5분이 지나면 다음 사람에게. 시작 30분 전부터는 넘기지 않고 그냥 빈 칸으로 푼다</li>
 *   <li>받은 예약도 노쇼 규칙은 보통과 같다. 하나를 받으면 그 사람의 다른 기다리기·넘김은 풀린다</li>
 * </ol>
 */
public interface RelayUseCase {

    /** 내 기다리기 (줄 서 있는 것만, 시작 전) */
    List<Wait> myWaits(long userId);

    /** 기다리기 걸기. 같은 칸에 이미 걸었으면 그것을 그대로 돌려준다 */
    Wait addWait(long userId, int roomId, Instant startAt);

    /** 그만 기다리기 (줄 서 있는 것만) */
    void cancelWait(long userId, long waitId);

    /** 나에게 넘어온 자리 (받을 시간이 남은 것) */
    List<Offer> myOffers(long userId);

    /** 넘길게요 → 다음 사람 */
    void decline(long userId, long offerId);

    /** 예약이 취소됐을 때 (취소 커밋 뒤). 시작 30분 전보다 이르면 줄 첫 사람에게 넘긴다 */
    void released(long reservationId);

    /** 받을 시간이 지난 넘김을 다음 사람에게 (1분 정리, 받기·넘기기 뒤). 넘긴 수 */
    int advance(Instant now);

    /** @param order 같은 칸 줄에서 내 순서 (1 = 맨 앞) */
    record Wait(long id, int roomId, ZonedDateTime startAt, int order) {
    }

    record Offer(long id, int roomId, ZonedDateTime startAt, ZonedDateTime endAt, ZonedDateTime expiresAt, int order) {
    }
}
