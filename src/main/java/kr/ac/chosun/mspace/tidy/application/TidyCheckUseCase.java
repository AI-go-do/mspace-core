package kr.ac.chosun.mspace.tidy.application;

import java.time.Instant;
import java.util.List;

/**
 * 퇴실 뒤 정리 확인 (FR-DEV-105). 방 카메라(젯슨)가 부른다. 결과·물건 이름·확신도만 받는다.
 * 11/10 10차 회의: 종류 구분 없이 "두고 감" 하나로, 2시간 안에 안 가져가면 경고.
 * 예약마다 FIRST(퇴실 직후)와 RECHECK(2시간 뒤) 한 번씩 받는다. 형식: AI-go-do/docs api/README.md 6-3.
 */
public interface TidyCheckUseCase {

    /** NEEDS_CLEANING은 11/10 전 기록만 (그때는 바로 어질러짐 신고). 지금은 받지 않는다 */
    enum Result { CLEAN, LEFT_ITEMS, NEEDS_CLEANING }

    /** FIRST: 퇴실 직후. RECHECK: FIRST가 LEFT_ITEMS면 recheck.at에 방이 비어 있을 때 한 번 더 */
    enum Phase { FIRST, RECHECK }

    /** 11/10 전 기록의 귀중품 칸 (가방·휴대폰·무선 이어폰·노트북). 지금은 받지 않는다 */
    enum LeftItem { BAG, PHONE, EARBUDS, LAPTOP }

    /** 장치가 본 물건 하나. confidence 0~1. 11/10 전 기록은 null */
    record Item(String name, Float confidence) {
    }

    /** 지금 걸린 예약 */
    record CurrentReservation(long reservationId, String status, Instant startAt, Instant endAt) {
    }

    /** 가장 최근에 끝난(입실했다 나간) 예약 */
    record LastEnded(long reservationId, String status, Instant endedAt) {
    }

    /** 이 예약을 at에 다시 확인해 달라는 요청. 그 사이 다음 예약이 입실하면 사라진다 (경고 없음) */
    record Recheck(long reservationId, Instant at) {
    }

    record RoomNow(int roomId, CurrentReservation current, LastEnded lastEnded, Recheck recheck) {
    }

    /** 젯슨이 10초마다 묻는다. lastEnded가 바뀌면 FIRST, recheck.at이 지나면 RECHECK를 보낸다 */
    RoomNow current(int roomId);

    record TidyCheck(String eventId, int roomId, long reservationId, String deviceId, Phase phase, Result result,
                     List<Item> items, Instant observedAt) {
    }

    /** @param duplicate 같은 eventId를 다시 받음 (처음 결과를 그대로 돌려준다) */
    record Recorded(boolean duplicate, Long reportId) {
    }

    Recorded submit(TidyCheck check);

    int DEFAULT_DAYS = 7;
    int MAX_DAYS = 92;

    /**
     * 관리자 정리 확인 목록의 한 줄.
     * @param endedAt  그 예약이 퇴실한 시각
     * @param reportId 11/10 전 NEEDS_CLEANING 기록이 자동으로 만든 신고. 그 밖에는 null
     */
    record Listed(long reservationId, int roomId, String roomName, Instant endedAt, Phase phase, Result result,
                  List<Item> items, List<LeftItem> leftItems, Long reportId, Instant observedAt) {
    }

    /** 관리자만(403 ADMIN_ONLY). 최근 days일(1–{@value MAX_DAYS}, 기본 {@value DEFAULT_DAYS})에 받은 결과, 최근 순 */
    List<Listed> list(long adminId, Integer days);
}
