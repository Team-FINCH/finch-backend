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
 * <p>
 * upstream 401·403·429 의 재포장(apiSpec 10.4)은 code 가 백엔드 enum(AI_UPSTREAM_*)이지만 <b>이 예외</b>로 던진다 —
 * AI 가 응답은 했으므로 requestId 를 보존해야 하고(contracts C70), 429 는 Retry-After 헤더까지 실어야 하는데
 * CustomException 경로에는 둘 다 실을 자리가 없다.
 */
@Getter
public class AiRelayException extends RuntimeException {

	private final HttpStatusCode status;
	private final String code;
	private final Object detail;
	private final String requestId;
	/** {@code Retry-After} 헤더 값(초). 503 {@code AI_UPSTREAM_RATE_LIMITED} 에만 있다 (apiSpec 10.4). null 이면 헤더를 싣지 않는다. */
	private final Long retryAfterSeconds;

	public AiRelayException(HttpStatusCode status, String code, String message, Object detail, String requestId) {
		this(status, code, message, detail, requestId, null);
	}

	public AiRelayException(HttpStatusCode status, String code, String message, Object detail, String requestId,
		Long retryAfterSeconds) {
		super(message);
		this.status = status;
		this.code = code;
		this.detail = detail;
		this.requestId = requestId;
		this.retryAfterSeconds = retryAfterSeconds;
	}
}
