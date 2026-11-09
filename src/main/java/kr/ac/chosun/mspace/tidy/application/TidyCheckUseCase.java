package kr.ac.chosun.mspace.tidy.application;

import java.time.Instant;
import java.util.List;

/**
 * 퇴실 뒤 정리 확인 (FR-DEV-105). 방 카메라(젯슨)가 부른다. 사진은 받지 않고 결과·물건 이름만 받는다.
 * 형식: AI-go-do/docs api/README.md 6-3.
 */
public interface TidyCheckUseCase {

    enum Result { CLEAN, NEEDS_CLEANING }

    /** 두고 가면 알림을 보내는 귀중품 (11/6 지영: 가방·휴대폰·무선 이어폰, 수연 의견으로 노트북 추가) */
    enum LeftItem { BAG, PHONE, EARBUDS, LAPTOP }

    /** 지금 걸린 예약 */
    record CurrentReservation(long reservationId, String status, Instant startAt, Instant endAt) {
    }

    /** 가장 최근에 끝난(입실했다 나간) 예약 */
    record LastEnded(long reservationId, String status, Instant endedAt) {
    }

    record RoomNow(int roomId, CurrentReservation current, LastEnded lastEnded) {
    }

    /** 젯슨이 10초마다 묻는다. lastEnded가 바뀌면 정리 확인을 시작한다 */
    RoomNow current(int roomId);

    record TidyCheck(String eventId, int roomId, long reservationId, String deviceId, Result result,
                     List<String> items, List<LeftItem> leftItems, Instant observedAt) {
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
     * @param reportId 치울 것이 있어 자동으로 만든 신고. 깨끗했으면 null
     */
    record Listed(long reservationId, int roomId, String roomName, Instant endedAt, Result result, List<String> items,
                  List<LeftItem> leftItems, Long reportId, Instant observedAt) {
    }

    /** 관리자만(403 ADMIN_ONLY). 최근 days일(1–{@value MAX_DAYS}, 기본 {@value DEFAULT_DAYS})에 받은 결과, 최근 순 */
    List<Listed> list(long adminId, Integer days);
}
