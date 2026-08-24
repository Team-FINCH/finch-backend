package com.finch.global.exception;

import com.finch.global.apiPayload.ErrorResponse;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 에러 응답을 만드는 유일한 지점이다.
 * 컨트롤러에서 try-catch 로 응답을 조립하지 않는다 (backConvention 5장).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(CustomException.class)
	public ResponseEntity<ErrorResponse> handleCustom(CustomException e) {
		return ResponseEntity.status(e.getErrorCode().getStatus())
			.body(ErrorResponse.of(e.getErrorCode(), e.getDetail()));
	}

	/** AI 서버 에러는 코드·문구·상태를 그대로 통과시키고 requestId 를 보존한다 (apiSpec 10.3, 10.4). */
	@ExceptionHandler(AiRelayException.class)
	public ResponseEntity<ErrorResponse> handleAiRelay(AiRelayException e) {
		return ResponseEntity.status(e.getStatus())
			.body(ErrorResponse.ofAiRelay(e.getCode(), e.getMessage(), e.getDetail(), e.getRequestId()));
	}

	/** 검증 실패의 detail 은 {필드명: 사유} 맵이다 (apiSpec 1.3). */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (FieldError error : e.getBindingResult().getFieldErrors()) {
			fields.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		return ResponseEntity.status(GeneralErrorCode.INVALID_REQUEST.getStatus())
			.body(ErrorResponse.of(GeneralErrorCode.INVALID_REQUEST, fields));
	}

	/**
	 * 예상하지 못한 예외. 원본 메시지를 본문에 담지 않는다 —
	 * message 는 사용자에게 그대로 노출되는 값이라 내부 정보가 새면 안 된다 (apiSpec 1.3).
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("처리되지 않은 예외", e);
		return ResponseEntity.status(GeneralErrorCode.INTERNAL_ERROR.getStatus())
			.body(ErrorResponse.of(GeneralErrorCode.INTERNAL_ERROR));
	}
}
