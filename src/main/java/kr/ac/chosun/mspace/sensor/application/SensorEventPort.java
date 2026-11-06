package kr.ac.chosun.mspace.sensor.application;

import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.sensor.application.RecordSensorEventUseCase.SensorEvent;

/** 센서 이벤트·방 재실 상태 저장. 구현은 adapter/out/persistence */
public interface SensorEventPort {

    /** 있는 방인가 (없는 방 번호는 400) */
    boolean roomExists(int roomId);

    /** 이미 저장된 eventId인가 (재전송 거르기) */
    boolean exists(String eventId);

    /** sensor_event에 넣는다. 같은 eventId가 이미 있으면(동시 재전송) false */
    boolean insert(SensorEvent event, Instant receivedAt);

    /** 방의 지금 상태 줄을 잠그고 읽는다. 없으면 "처음" 줄(있음, 시각은 모두 EPOCH)을 만든 뒤 잠근다 */
    Presence lockPresence(int roomId);

    void savePresence(Presence presence);

    /** 같은 방 CO2 값 최근 n개 (observedAt 늦은 것부터) */
    List<Co2Reading> recentCo2(int roomId, int limit);

    /** 지금 그 방을 쓰는 예약(IN_USE)의 대표자·참여자 */
    List<Occupant> occupantsNow(int roomId, Instant now);

    /** 방의 지금 재실 상태 (room_presence 한 줄). 시각 뜻은 V11__sensor_events.sql 주석 */
    record Presence(int roomId, boolean occupied, Instant changedAt, Instant trustedFrom, Instant lastObservedAt,
                    Instant lastReceivedAt, Instant ventilationAlertAt) {
    }

    record Co2Reading(String eventId, int ppm, Instant observedAt) {
    }

    record Occupant(long reservationId, long userId, String roomName) {
    }
}
