package kr.ac.chosun.mspace.common.error;

import org.springframework.http.HttpStatus;

/** 오류 코드 한곳 모음. 응답은 {"code": name(), "message": message} 모양 (NFR-COM-005). */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    ROOM_DATE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "오늘부터 예약 가능한 기간 안의 날짜만 조회할 수 있습니다"),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "없거나 지금 사용할 수 없는 방입니다"),

    // 예약 생성 (FR-RSV-001~005)
    RESERVATION_NOT_ON_SLOT(HttpStatus.BAD_REQUEST, "시작·종료 시각은 정각 또는 30분이어야 합니다"),
    RESERVATION_INVALID_LENGTH(HttpStatus.BAD_REQUEST, "종료는 시작보다 뒤여야 하고, 1회 최대 2시간입니다"),
    RESERVATION_IN_PAST(HttpStatus.BAD_REQUEST, "지난 시각은 예약할 수 없습니다"),
    RESERVATION_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "오늘부터 7일 안에 시작하는 예약만 할 수 있습니다"),
    PARTICIPANT_DUPLICATE(HttpStatus.BAD_REQUEST, "같은 학번을 두 번 넣을 수 없습니다"),
    PARTICIPANT_NOT_FOUND(HttpStatus.BAD_REQUEST, "학생 인증을 마친 회원의 학번만 넣을 수 있습니다"),
    HEADCOUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "방의 이용 인원을 벗어났습니다"),
    RESERVATION_TIME_TAKEN(HttpStatus.CONFLICT, "이미 예약된 시간입니다"),
    OWNER_HAS_ACTIVE_RESERVATION(HttpStatus.CONFLICT, "끝나지 않은 예약이 이미 있습니다"),
    PARTICIPANT_TIME_TAKEN(HttpStatus.CONFLICT, "참여자 중 같은 시간에 다른 방 예약이 있는 사람이 있습니다"),
    // 동시 요청끼리 DB에서 서로 기다리는 교착이 다시 해도 계속 날 때 (드묾). 예약은 저장되지 않았다
    RESERVATION_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요"),

    // 예약 취소 (FR-RSV-007). 이름은 영준 비서(mspace-assistant #30)와 맞춤
    // 없는 예약과 남의 예약(참여자도 아님)은 둘 다 404: 남의 예약이 있는지 없는지 알려 주지 않으려고
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없는 예약입니다"),
    NOT_RESERVATION_OWNER(HttpStatus.FORBIDDEN, "대표자만 할 수 있습니다"),
    RESERVATION_NOT_CANCELABLE(HttpStatus.CONFLICT, "시작 전인 예약만 취소할 수 있습니다");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
