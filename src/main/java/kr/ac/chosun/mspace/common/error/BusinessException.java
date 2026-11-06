package kr.ac.chosun.mspace.common.error;

/** 업무 규칙 위반. 어디서 던져도 GlobalExceptionHandler가 {code, message}로 바꾼다. */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    /** 메시지에 숫자가 들어가야 할 때 (예: "이 방은 3명 이상 5명 이하입니다") */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
