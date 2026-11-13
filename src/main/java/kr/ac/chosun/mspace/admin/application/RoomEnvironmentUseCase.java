package kr.ac.chosun.mspace.admin.application;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 관리자 "지금" 탭 (요구사항 Q-21, 11/9 지영): 방별 지금 CO2·온도·습도·조도·PIR·재실과 지난 N분 1분 값.
 * 화면(web src/api/environment.ts)이 기대하는 모양에 맞춘다. 관리자만.
 */
public interface RoomEnvironmentUseCase {

    int DEFAULT_MINUTES = 60;
    int MAX_MINUTES = 180;

    RoomsEnvironment environment(long adminId, Integer minutes);

    record RoomsEnvironment(ZonedDateTime now, int minutes, List<RoomEnvironment> rooms) {
    }

    /**
     * @param presence     재실 (FR-DEV-101). 신호가 끊겼거나 한 번도 안 왔으면 UNKNOWN (방 현황과 같은 기준)
     * @param lastSignalAt 재실·CO2 중 마지막으로 받은 시각. 한 번도 없으면 null
     * @param offline      lastSignalAt이 mspace.sensor.offline-after-seconds보다 오래됐거나 없으면 true
     * @param co2          가장 최근 CO2 값 (창 밖이어도 마지막 값). 한 번도 없으면 null
     * @param light        조도 센서 원시값(0–1023, 밝을수록 큼. lux 아님). 지난 minutes분 안에 받은 마지막 값, 없으면 null
     * @param lightLeftOn  빈 방(재실 없음·지금 예약 없음)인데 조도가 기준 이상으로 after(10분) 넘게 이어지면 "불 켜짐".
     *                     since = 처음 켜짐을 본 시각. 아니면 null (11/12 지영)
     * @param pir          PIR 움직임 감지. 지난 minutes분 안에 받은 마지막 값, 없으면 null. 둘 다 보여 주기만(재실 판단엔 안 씀)
     * @param series       지난 minutes분, 1분마다 한 줄(오래된 것이 앞). 그 분에 값이 없으면 null
     * @param current      지금 시각을 낀 예약(입실 전 포함). 없으면 null. people = 예약 인원
     * @param next         지금 뒤 가장 가까운 예약. 없으면 null
     */
    record RoomEnvironment(int roomId, String roomName, String presence, ZonedDateTime lastSignalAt, boolean offline,
                           Co2 co2, Double temperatureC, Double humidityPct, Light light, LightLeftOn lightLeftOn, Pir pir, List<Point> series,
                           Current current, Next next) {
    }

    record Co2(int ppm, ZonedDateTime at) {
    }

    /** @param raw 0–1023 원시값 */
    record Light(int raw, ZonedDateTime at) {
    }

    /** @param since 빈 방에서 불 켜짐을 처음 본 시각 */
    record LightLeftOn(ZonedDateTime since) {
    }

    /** @param detected 움직임 있음 */
    record Pir(boolean detected, ZonedDateTime at) {
    }

    /** 1분 평균. ppm은 정수로 반올림, 온도·습도는 소수 한 자리 */
    record Point(ZonedDateTime at, Integer ppm, Double temperatureC, Double humidityPct) {
    }

    /** @param checkedIn 입실했으면(IN_USE) true. 아직 입실 전(BOOKED)이면 false라 관리자 화면이 테스트용 입실 확인 버튼을 보인다 */
    record Current(long reservationId, ZonedDateTime startAt, ZonedDateTime endAt, int people, boolean checkedIn) {
    }

    record Next(ZonedDateTime startAt, int people) {
    }
}
