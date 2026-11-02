package kr.ac.chosun.mspace.common.error;

import org.springframework.http.HttpStatus;

/** 오류 코드 한곳 모음. 응답은 {"code": name(), "message": message} 모양 (NFR-COM-005). */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다"),
    ROOM_DATE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "오늘부터 예약 가능한 기간 안의 날짜만 조회할 수 있습니다");

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
