package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.List;

/** 취소 가능성 계산에 쓰는 읽기 전용 조회 */
public interface RelayChancePort {

    /** 그날 from보다 뒤에 시작하고(릴레이로 넘길 수 있는 예약) 이 사람이 들어 있지 않은 미리 예약(BOOKED) */
    List<Booked> bookedOthers(Instant dayStart, Instant dayEnd, Instant from, long userId);

    /**
     * [since, until)에 시작한 미리 예약을 서울 기준 요일(1=월 … 7=일)·시작 시(0–23)로 묶어 센다.
     * canceled = 시작 stopBefore분 전까지 취소된 것
     */
    List<SlotHistory> history(Instant since, Instant until, int stopBeforeMinutes);

    /** 이 방 [start, end) 안의 30분 칸에 줄 선(WAITING) 사람 수 */
    int waiting(int roomId, Instant start, Instant end);

    record Booked(int roomId, Instant startAt, Instant endAt, Instant createdAt) {
    }

    record SlotHistory(int dayOfWeek, int hour, long canceled, long total) {
    }
}
