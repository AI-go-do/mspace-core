package kr.ac.chosun.mspace.common.error;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 모든 컨트롤러의 오류를 팀 형식 {"code": "...", "message": "..."}으로 바꾼다.
 * 본문 형식을 JSON으로 못 박는다: 사진 API(GET .../tidy-photos/{phase}, .../photos/{phase})는 화면이 Accept: image/*로 부르는데,
 * 형식을 정하지 않으면 Spring이 그 Accept에 맞춰 JSON 오류를 쓸 수 없어 404 PHOTO_NOT_FOUND가 500으로 바뀌었다(11/13 리허설)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 업무 규칙 오류 → ErrorCode에 정한 상태 코드(400·401·404·409)와 본문 */
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.errorCode().status()).contentType(MediaType.APPLICATION_JSON).body(ErrorResponse.of(e));
    }

    /** ?date=2026-13-40 같은 형식 오류, 필수 파라미터 누락, 읽을 수 없는 JSON 본문 → 400 */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON).body(ErrorResponse.of(code));
    }

    /** 업로드가 spring.servlet.multipart 상한(3MB)을 넘음 → 사진 크기 오류 (FR-RPT-102) */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        ErrorCode code = ErrorCode.PHOTO_TOO_LARGE;
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON).body(ErrorResponse.of(code));
    }
}
