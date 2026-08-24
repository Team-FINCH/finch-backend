package com.finch.global.exception;

import com.finch.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;

/** 백엔드가 스스로 판단한 실패. 서비스 계층에서 던지면 GlobalExceptionHandler 가 응답 형식을 맞춘다. */
@Getter
public class CustomException extends RuntimeException {

	private final BaseErrorCode errorCode;
	private final Object detail;

	public CustomException(BaseErrorCode errorCode) {
		this(errorCode, null);
	}

	public CustomException(BaseErrorCode errorCode, Object detail) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
		this.detail = detail;
	}
}
