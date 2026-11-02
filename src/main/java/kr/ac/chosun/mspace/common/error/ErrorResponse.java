package kr.ac.chosun.mspace.common.error;

public record ErrorResponse(String code, String message) {

    static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.message());
    }
}
