package kr.ac.chosun.mspace.ops.application;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * AI 자동 처리 (11/12 지영). 숫자 규칙 5개를 1분마다 보고, 학생 알림 3종은 보내고 관리자용 2종은 기록만 한다.
 * 관리자는 처리한 일을 보기만 한다.
 */
public interface OpsAutoUseCase {

    /** 1분마다 부른다. 새로 처리한 일 수 */
    int check();

    /** 관리자 "AI가 처리한 일": 그날(서울) 처리한 일, 최근 것부터. 관리자만 */
    List<OpsAction> actions(long adminId, LocalDate date);

    /**
     * @param kind     VENT_SOON 미리 환기 · ROOM_HOT 예약 전 더움 · SENSOR_DOWN 센서 고장 · OVERTIME 시간 넘김 · UNBOOKED 예약 없는 사용
     *                 · LIGHT_LEFT_ON 불 켜짐(sensor 기능이 OpsActionRecorder로 남김, 11/13)
     * @param notified 알림을 보낸 사람 수 (SENSOR_DOWN·UNBOOKED는 기록만이라 0)
     */
    record OpsAction(long id, String kind, int roomId, String roomName, Long reservationId, ZonedDateTime startedAt,
                     ZonedDateTime detectedAt, String detail, int notified) {
    }
}
