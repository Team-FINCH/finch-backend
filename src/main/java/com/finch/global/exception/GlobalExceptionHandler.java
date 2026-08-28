package com.finch.global.exception;

import com.finch.global.apiPayload.ErrorResponse;
import com.finch.global.apiPayload.code.BaseErrorCode;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

	/** 매핑되지 않은 경로. 정적 리소스 핸들러가 던지는 예외라 이름이 낯설지만 실체는 404 다. */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
		return respond(GeneralErrorCode.RESOURCE_NOT_FOUND, null);
	}

	/**
	 * 본문을 JSON 으로 읽지 못한 경우. 파서 메시지에는 클래스명·필드 경로가 섞여 있어
	 * detail 로 내보내지 않는다 — message 는 사용자에게 그대로 노출된다 (apiSpec 1.3).
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
		return respond(GeneralErrorCode.INVALID_REQUEST, null);
	}

	/** 필수 쿼리 파라미터 누락. detail 은 본문 검증과 같은 {이름: 사유} 모양이다. */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
		return respond(GeneralErrorCode.INVALID_REQUEST, Map.of(e.getParameterName(), "필수 값입니다"));
	}

	/** 쿼리·경로 파라미터의 타입 불일치 (예: size=abc, period=2W 같은 enum 값 밖). */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return respond(GeneralErrorCode.INVALID_REQUEST, Map.of(e.getName(), "형식이 올바르지 않습니다"));
	}

	/**
	 * @RequestParam·@PathVariable 에 붙인 제약(@Min 등) 위반.
	 * 본문 검증(MethodArgumentNotValidException)과 달리 BindingResult 가 없어 결과를 직접 돌며
	 * 같은 {이름: 사유} 모양으로 맞춘다. 프론트가 두 경우를 구분할 이유가 없다.
	 */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorResponse> handleParameterValidation(HandlerMethodValidationException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (ParameterValidationResult result : e.getParameterValidationResults()) {
			String name = result.getMethodParameter().getParameterName();
			for (MessageSourceResolvable error : result.getResolvableErrors()) {
				fields.putIfAbsent(name != null ? name : "parameter", error.getDefaultMessage());
			}
		}
		return respond(GeneralErrorCode.INVALID_REQUEST, fields);
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
