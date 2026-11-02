package kr.ac.chosun.mspace.common.error;

/** 업무 규칙 위반. 어디서 던져도 GlobalExceptionHandler가 {code, message}로 바꾼다. */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
