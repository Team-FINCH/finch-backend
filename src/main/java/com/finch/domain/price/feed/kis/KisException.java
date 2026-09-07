package com.finch.domain.price.feed.kis;

/**
 * KIS 호출 실패. 사용자에게 나가는 에러가 아니다 — 폴링 워커와 배치가 잡아 로그를 남기고 다음 주기로 넘어간다.
 * 그래서 {@code CustomException} 이 아니고 에러 코드도 없다.
 *
 * @param kind 호출자가 분기할 종류. {@code RATE_LIMITED} 면 그 키의 이번 순회를 멈춘다.
 */
public class KisException extends RuntimeException {

	public enum Kind {
		/** 초당 한도 초과. Retry-After 만큼 기다려 1회 재시도한 뒤에도 그러면 이것이다. */
		RATE_LIMITED,
		/** 토큰 무효·만료. 재발급 후 1회 재시도한 뒤에도 그러면 이것이다. */
		UNAUTHORIZED,
		/** KIS 가 {@code rt_cd != 0} 로 거절했거나 응답을 읽지 못했다. */
		REJECTED,
		/** 네트워크·타임아웃. */
		UNAVAILABLE
	}

	private final Kind kind;

	public KisException(Kind kind, String message) {
		super(message);
		this.kind = kind;
	}

	public KisException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public Kind getKind() {
		return kind;
	}
}
