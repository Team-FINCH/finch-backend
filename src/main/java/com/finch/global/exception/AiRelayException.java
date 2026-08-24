package com.finch.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

/**
 * AI 서버가 반환한 에러를 그대로 통과시키기 위한 예외 (apiSpec 10.4).
 * <p>
 * code 가 String 인 이유는 AI 서버가 발행하는 코드(INSUFFICIENT_DATA, GUARDRAIL_BLOCKED 등)가
 * 백엔드 enum 에 없기 때문이다. 목록은 aiApiSpec 3장이 관리한다.
 * 상태 코드까지 그대로 넘긴다 — 백엔드가 자기 5xx 로 뭉개면 프론트가 AI 위젯만 따로 처리할 수 없다.
 * <p>
 * 백엔드가 AI 서버에 닿지 못한 경우는 이 예외가 아니라
 * CustomException 으로 AI_UPSTREAM_UNAVAILABLE / AI_UPSTREAM_TIMEOUT 을 던진다.
 */
@Getter
public class AiRelayException extends RuntimeException {

	private final HttpStatusCode status;
	private final String code;
	private final Object detail;
	private final String requestId;

	public AiRelayException(HttpStatusCode status, String code, String message, Object detail, String requestId) {
		super(message);
		this.status = status;
		this.code = code;
		this.detail = detail;
		this.requestId = requestId;
	}
}
