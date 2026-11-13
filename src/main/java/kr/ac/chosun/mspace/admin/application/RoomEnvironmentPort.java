package kr.ac.chosun.mspace.admin.application;

import java.time.Instant;
import java.util.List;

/** 관리자 "지금" 탭 저장소. 구현: JdbcRoomEnvironmentAdapter */
public interface RoomEnvironmentPort {

    boolean isAdmin(long userId);

    /** 사용 중인 방 (rooms.is_active), 번호 순 */
    List<Room> rooms();

    /** 방마다 재실 상태 줄 (room_presence). 한 번도 신호가 없던 방은 없다 */
    List<PresenceRow> presence();

    /** 방마다 가장 최근 CO2 값과 가장 최근 온도·습도(값이 있는 것), 마지막 CO2 받은 시각 */
    List<Latest> latest();

    /** [from, to) 1분 칸마다 방별 평균. 값이 없는 칸도 null로 한 줄씩 */
    List<MinuteRow> series(Instant from, Instant to);

    /** to 뒤에 끝나는 BOOKED·IN_USE 예약, 시작 순 */
    List<Booking> bookingsEndingAfter(Instant at);

    record Room(int id, String name) {
    }

    record PresenceRow(int roomId, boolean occupied, Instant lastReceivedAt) {
    }

    record Latest(int roomId, Integer ppm, Instant ppmAt, Double temperatureC, Double humidityPct, Instant lastReceivedAt) {
    }

    record MinuteRow(int roomId, Instant at, Integer ppm, Double temperatureC, Double humidityPct) {
    }

    record Booking(long id, int roomId, Instant startAt, Instant endAt, int headcount, boolean checkedIn) {
    }
}
