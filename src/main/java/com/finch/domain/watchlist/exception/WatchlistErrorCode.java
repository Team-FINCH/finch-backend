package com.finch.domain.watchlist.exception;

import com.finch.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** apiSpec 11장 "관심 종목" 목록. 최대 개수는 apiSpec 6.3 (50개). */
@Getter
@RequiredArgsConstructor
public enum WatchlistErrorCode implements BaseErrorCode {

	WATCHLIST_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "관심 종목은 최대 50개까지 등록할 수 있어요"),
	WATCHLIST_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 관심 종목에 등록된 종목이에요");

	private final HttpStatus status;
	private final String message;

	/** 코드 문자열은 enum 이름이다. 이유는 GeneralErrorCode 참고. */
	@Override
	public String getCode() {
		return name();
	}
}
