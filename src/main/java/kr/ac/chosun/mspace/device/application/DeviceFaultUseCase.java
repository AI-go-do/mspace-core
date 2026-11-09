package kr.ac.chosun.mspace.device.application;

import java.time.Instant;
import java.util.List;

/**
 * 문 장치 오류 기록 (방의 사과, Q-19). 장치는 끊긴 동안 오류 구간과 그동안 눌린 번호를 모아 뒀다가 다시 연결되면 보낸다.
 * 번호는 "그 시간 그 방 예약"을 찾는 데만 쓰고 저장하지 않는다. 키패드 실패 횟수(5번 잠금)에도 세지 않는다.
 * 찾은 예약이 노쇼면 무르고 사과 알림을 보낸다(노쇼 처리 전이면 1분 정리가 노쇼로 바꾼 뒤 무른다).
 */
public interface DeviceFaultUseCase {

    /** 같은 eventId는 한 번만 (다시 오면 DUPLICATE, 아무것도 안 바꿈) */
    Recorded record(Fault fault);

    enum Kind {
        /** 서버에 연결 자체가 안 됨 (와이파이 끊김 포함) */
        SERVER_UNREACHABLE,
        /** 서버가 5xx로 답함 */
        SERVER_ERROR,
        /** 젯슨·게이트웨이가 꺼졌다 켜짐 (꺼진 구간) */
        DEVICE_RESTART,
        /** 문 앞 QR 카메라가 화면을 못 읽음 */
        CAMERA_DOWN,
        /** 키패드 연결 끊김 */
        KEYPAD_DOWN
    }

    enum Via { KEYPAD, QR }

    record Attempt(Instant at, String code, Via via) {
    }

    record Fault(String eventId, int roomId, String deviceId, Kind kind, Instant startedAt, Instant endedAt,
                 List<Attempt> attempts) {
    }

    /**
     * @param matched 눌린 번호 중 예약을 찾은 수
     * 무르기는 1분 정리가 한다 (응답 시간으로 번호가 맞았는지 알 수 없게)
     */
    record Recorded(boolean duplicate, int matched) {
    }
}
