package kr.ac.chosun.mspace.tidy.application;

import java.time.Instant;
import java.util.Optional;

/**
 * 두고 간 물건 찾으러 들어가는 10분 QR (FR-DEV-105, 11/10 지영). 구현: {@link RetrievePassService}
 * <ul>
 *   <li>받는 사람: 그 예약의 대표자·참여자 (두고 간 물건 알림을 받은 사람)</li>
 *   <li>조건: 퇴실 직후 확인(FIRST)이 LEFT_ITEMS이고 퇴실 뒤 mspace.tidy.recheck-after-minutes 안.
 *       다음 팀이 방을 쓰고 있어도 준다 (11/10 지영 "다음 팀이 쓰고 있어도 퇴실 뒤 3시간")</li>
 *   <li>예약마다 한 번에 하나. 살아 있는 동안 다시 누르면 같은 QR, 지나면 새 번호</li>
 * </ul>
 */
public interface RetrievePassUseCase {

    /** "찾으러 갈게요". 없거나 내 예약이 아니면 404, FIRST가 LEFT_ITEMS가 아니면 409, 기한이 지났으면 409 */
    Pass issue(long reservationId, long userId);

    /**
     * 문 앞(키패드·QR)에서 받은 번호가 이 방의 살아 있는 찾으러 온 QR인지. 맞으면 처음 쓴 시각을 남기고 그 예약 id.
     * access 기능의 POST /api/access/verify가 예약 번호로 못 찾았을 때 부른다 (같은 트랜잭션)
     */
    Optional<Long> open(int roomId, String code);

    record Pass(long reservationId, int roomId, String code, Instant expiresAt) {
    }
}
