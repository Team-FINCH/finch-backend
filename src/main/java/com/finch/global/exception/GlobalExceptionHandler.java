package com.finch.global.exception;

import com.finch.global.apiPayload.ErrorResponse;
import com.finch.global.apiPayload.code.BaseErrorCode;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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
		return respond(e.getErrorCode(), e.getDetail());
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
		return respond(GeneralErrorCode.INVALID_REQUEST, fields);
	}

	/**
	 * 존재하는 경로에 허용되지 않은 메서드로 온 요청 (apiSpec 11장 공통, v0.3 추가).
	 * 이 핸들러가 없으면 Exception 핸들러로 떨어져 405 가 500 으로 둔갑한다.
	 * Allow 헤더는 HTTP 규격(RFC 9110 §15.5.6)이 405 응답에 요구하는 값이라 그대로 싣는다.
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(GeneralErrorCode.METHOD_NOT_ALLOWED.getStatus());
		Set<HttpMethod> allowed = e.getSupportedHttpMethods();
		if (allowed != null && !allowed.isEmpty()) {
			builder.allow(allowed.toArray(HttpMethod[]::new));
		}
		return builder.body(ErrorResponse.of(GeneralErrorCode.METHOD_NOT_ALLOWED));
	}

	/** 본문을 받는 엔드포인트에 application/json 이 아닌 Content-Type 으로 온 요청 (apiSpec 11장 공통). */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
		return respond(GeneralErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
	}

	/**
	 * 예상하지 못한 예외. 원본 메시지를 본문에 담지 않는다 —
	 * message 는 사용자에게 그대로 노출되는 값이라 내부 정보가 새면 안 된다 (apiSpec 1.3).
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("처리되지 않은 예외", e);
		return respond(GeneralErrorCode.INTERNAL_ERROR, null);
	}

	private static ResponseEntity<ErrorResponse> respond(BaseErrorCode errorCode, Object detail) {
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode, detail));
	}
}
