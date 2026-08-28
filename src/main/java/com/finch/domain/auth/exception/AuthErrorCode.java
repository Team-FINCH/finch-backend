package com.finch.domain.auth.exception;

import com.finch.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * apiSpec 11장 "인증" 목록. 어느 코드가 언제 나가는지는 apiSpec 11장 엔드포인트별 발생 코드 표를 따른다.
 * <p>
 * 프론트 인터셉터는 TOKEN_EXPIRED·INVALID_TOKEN·REFRESH_TOKEN_MISSING 세 코드에서만 세션에 손을 댄다.
 * 이 셋의 문자열을 바꾸면 로그인 흐름이 조용히 깨진다.
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {

	AUTH_KAKAO_FAILED(HttpStatus.UNAUTHORIZED, "카카오 로그인에 실패했습니다. 다시 시도해 주세요"),
	AUTH_REFRESH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "로그인 정보가 없습니다"),
	AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "인증 정보가 올바르지 않습니다. 다시 로그인해 주세요"),
	AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증이 만료되었습니다"),
	AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다");

	private final HttpStatus status;
	private final String message;

	/** 코드 문자열은 enum 이름이다. 이유는 GeneralErrorCode 참고. */
	@Override
	public String getCode() {
		return name();
	}
}
