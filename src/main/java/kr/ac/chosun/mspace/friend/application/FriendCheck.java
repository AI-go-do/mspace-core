package kr.ac.chosun.mspace.friend.application;

import java.util.Collection;

/**
 * 예약(reservation 기능)이 부르는 참여자 확인 (FR-RSV-004, 11/6 지영): 참여자는 대표자와 서로 친구여야 한다.
 * 설정 mspace.reservation.friends-only=false면 확인하지 않는다(화면에 친구 창이 붙기 전 개발 서버 등).
 */
@FunctionalInterface
public interface FriendCheck {

    /** memberIds를 ownerId 예약의 참여자로 넣어도 되면 true. 비어 있으면 true */
    boolean canAddAsParticipants(long ownerId, Collection<Long> memberIds);
}
