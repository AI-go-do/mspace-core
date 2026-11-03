package kr.ac.chosun.mspace.common.error;

import org.springframework.http.HttpStatus;

/** 오류 코드 한곳 모음. 응답은 {"code": name(), "message": message} 모양 (NFR-COM-005). */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다"),
    ROOM_DATE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "오늘부터 예약 가능한 기간 안의 날짜만 조회할 수 있습니다"),

    // 로그인 (FR-AUTH-001)
    SCHOOL_EMAIL_ONLY(HttpStatus.BAD_REQUEST, "학교 메일(@chosun.ac.kr)만 사용할 수 있습니다"),
    EMAIL_CODE_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 1분에 한 번만 보낼 수 있습니다"),
    EMAIL_CODE_NOT_FOUND(HttpStatus.BAD_REQUEST, "인증번호를 먼저 받아 주세요"),
    EMAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다. 다시 받아 주세요"),
    EMAIL_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증번호가 맞지 않습니다"),
    EMAIL_CODE_TOO_MANY_ATTEMPTS(HttpStatus.BAD_REQUEST, "5번 틀려서 이 인증번호는 더 쓸 수 없습니다. 다시 받아 주세요"),
    SIGNUP_INFO_REQUIRED(HttpStatus.BAD_REQUEST, "처음 로그인이라 이름과 학번이 필요합니다"),
    INVALID_SIGNUP_INFO(HttpStatus.BAD_REQUEST, "이름(50자 이내)과 학번(숫자 20자 이내)을 확인해 주세요"),
    STUDENT_ID_TAKEN(HttpStatus.CONFLICT, "이미 가입된 학번입니다"),
    EMAIL_ALREADY_SIGNED_UP(HttpStatus.CONFLICT, "이 메일로 방금 가입되었습니다. 인증번호를 다시 받아 로그인해 주세요"),
    MAIL_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "인증번호 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요");

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
