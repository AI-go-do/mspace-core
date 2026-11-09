package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 빈방 릴레이 DB 일 (V24). 구현은 adapter/out/persistence */
public interface RelayPort {

    /** 방 잠금 (예약 저장과 같은 번호 -방 id). 넘김 만들기와 예약 저장이 한 줄로 서게 */
    void lockRoom(int roomId);

    /** 넘기기 일(advance) 잠금. advance끼리 한 줄로 서게 해서 여러 방 잠금을 서로 다른 순서로 잡는 교착을 막는다 */
    void lockAdvance();

    /** 회원 잠금 (예약 저장과 같은 번호 = 회원 id). 기다리기 3개 세기와 걸기가 한 줄로 서게 */
    void lockUser(long userId);

    /** 그 칸 [start, start+slot)을 덮는 끝나지 않은 예약이 있는지, 그 예약에 내가 들어 있는지 */
    SlotState slotState(int roomId, Instant start, Instant end, long userId);

    int countActiveWaits(long userId, Instant now);

    Optional<WaitRow> findActiveWait(long userId, int roomId, Instant startAt);

    WaitRow insertWait(long userId, int roomId, Instant startAt, Instant now);

    /** 줄 서 있는(WAITING) 내 기다리기를 CANCELED로. 바꿨으면 true */
    boolean cancelWait(long userId, long waitId, Instant now);

    /** 시작 전인 내 WAITING·OFFERED 기다리기와 줄 순서 */
    List<WaitRow> myWaits(long userId, Instant now);

    List<OfferRow> myOpenOffers(long userId, Instant now);

    Optional<OfferRow> findOffer(long offerId);

    /** 열린 넘김을 DECLINED로 (기다리기도 DECLINED). 바꿨으면 true */
    boolean declineOffer(long userId, long offerId, Instant now);

    /** 받을 시간이 지난 열린 넘김이 있는 방 (방 번호 순) */
    List<Integer> roomsWithExpiredOffers(Instant now);

    /** 이 방의 받을 시간이 지난 열린 넘김을 EXPIRED로 (기다리기도 DECLINED). 방 잠금 안에서 부른다. 바뀐 수 */
    int expireOffers(int roomId, Instant now);

    /** 시작이 from보다 늦고, 줄 선 사람이 있는데 넘김이 한 번도 안 만들어진 취소 예약 (첫 넘기기가 실패한 것) */
    List<Long> unofferedCancellations(Instant from);

    /** 아직 다음 사람을 찾지 않은 DECLINED·EXPIRED·TAKEN 넘김(TAKEN은 일부만 받았을 때 남은 칸 때문). 잠그고 읽는다 */
    List<OfferRow> offersToPass();

    void markPassed(long offerId);

    /** 이 구간과 겹치는 끝나지 않은(BOOKED·IN_USE) 예약 구간, 시작 순 */
    List<Span> busy(int roomId, Instant start, Instant end);

    /** 취소된 예약 (방·시작·끝). 취소 상태가 아니면 빈 값 */
    Optional<Released> canceledReservation(long reservationId);

    /**
     * 이 구간 안의 칸에 줄 선 첫 사람에게 넘김을 만든다(기다리기는 OFFERED). 줄이 비었으면 빈 값.
     * 같은 구간에 이미 열린 넘김이 있으면 만들지 않는다
     */
    Optional<OfferRow> offerToNext(int roomId, Instant start, Instant end, long sourceReservationId, Instant now,
                                   Instant expiresAt);

    /** 받기: 받을 시간이 남은(expires_at > now) 열린 넘김을 TAKEN으로, 그 사람의 다른 기다리기는 CANCELED, 다른 열린 넘김은 DECLINED. 바꿨으면 true */
    boolean take(long offerId, long userId, long reservationId, Instant now);

    String roomName(int roomId);

    record SlotState(boolean taken, boolean mine) {
    }

    record WaitRow(long id, int roomId, Instant startAt, int order) {
    }

    record OfferRow(long id, long userId, int roomId, Instant startAt, Instant endAt, Instant expiresAt,
                    String status, long sourceReservationId, int order) {
    }

    record Released(int roomId, Instant startAt, Instant endAt) {
    }

    record Span(Instant start, Instant end) {
    }
}
