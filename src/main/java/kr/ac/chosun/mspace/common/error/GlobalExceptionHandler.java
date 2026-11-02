package kr.ac.chosun.mspace.common.error;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.errorCode();
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    /** ?date=2026-13-40 같은 형식 오류, 필수 파라미터 누락 → 400 */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }
}
