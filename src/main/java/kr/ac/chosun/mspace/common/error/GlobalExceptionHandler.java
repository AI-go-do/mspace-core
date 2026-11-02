package kr.ac.chosun.mspace.common.error;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 모든 컨트롤러의 오류를 팀 형식 {"code": "...", "message": "..."}으로 바꾼다 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 업무 규칙 오류 → ErrorCode에 정한 상태 코드(400·401·404·409)와 본문 */
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.errorCode().status()).body(ErrorResponse.of(e));
    }

    /** ?date=2026-13-40 같은 형식 오류, 필수 파라미터 누락, 읽을 수 없는 JSON 본문 → 400 */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }
}
