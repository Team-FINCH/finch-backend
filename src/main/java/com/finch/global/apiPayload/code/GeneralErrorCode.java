package com.finch.global.apiPayload.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** apiSpec 11장의 "공통" 목록. 도메인 고유 코드는 각 도메인 패키지에 별도 enum 으로 둔다. */
@Getter
@RequiredArgsConstructor
public enum GeneralErrorCode implements BaseErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다"),
	IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "요청 식별자가 필요합니다"),
	IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요"),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 키로 다른 요청이 접수됐습니다"),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 요청 방식입니다"),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요");

	private final HttpStatus status;
	private final String message;

	/**
	 * 코드 문자열을 별도 필드로 두지 않는다.
	 * apiSpec 11장의 코드가 전부 대문자 스네이크라 enum 이름과 일치하고,
	 * 따로 두면 이름만 바꾸고 문자열은 그대로 두는 순간 프론트 분기가 조용히 죽는다.
	 */
	@Override
	public String getCode() {
		return name();
	}
}
