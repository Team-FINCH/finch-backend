package com.finch.domain.ai.relay;

import com.finch.domain.ai.AiProperties;
import com.finch.domain.ai.exception.AiErrorCode;
import com.finch.global.exception.AiRelayException;
import com.finch.global.exception.CustomException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 서버의 에러를 프론트로 어떻게 넘기나 (apiSpec 10.4). 기본 규칙은 <b>AI 가 응답한 에러는 code·message·detail·상태를 그대로
 * 통과</b>시키고 {@code request_id} 를 {@code requestId} 로 보존하는 것이다. 백엔드가 자기 5xx 로 뭉개면 프론트가 AI 위젯만 접고
 * 시세·주문을 살리는 에러 경계를 만들 수 없다.
 * <p>
 * 예외가 둘이다 (v0.8).
 * <ul>
 *   <li><b>401·403 → 502 {@code AI_UPSTREAM_UNAVAILABLE}</b>, {@code detail.reason=upstream_auth}. 그대로 흘리면 프론트 인증
 *       인터셉터가 사용자 토큰 만료로 오인해 로그아웃시킨다. 실제 원인은 백엔드↔AI 의 내부 토큰이고 사용자와 무관하다.</li>
 *   <li><b>429 → 503 {@code AI_UPSTREAM_RATE_LIMITED}</b> + {@code Retry-After}. 그대로 흘리면 사용자가 요청을 많이 보낸 것으로
 *       오인된다. 실제로는 백엔드 전체의 AI 호출량이 상한에 닿은 것이다. 간격은 AI 가 준 값, 없으면 기본 5초, 최소 1초.</li>
 * </ul>
 * 두 경우 AI 가 준 {@code detail} 은 버린다 — 내부 인증 실패 메시지에는 사용자에게 보여줄 것이 없다. {@code requestId} 는 있으면
 * 보존한다 (contracts C70).
 * <p>
 * AI 가 응답조차 하지 않은 경우는 백엔드 자체 코드다 — 연결 실패 502 {@code AI_UPSTREAM_UNAVAILABLE}, 타임아웃 504
 * {@code AI_UPSTREAM_TIMEOUT}. 둘 다 {@code requestId} 가 없다.
 */
@Slf4j
final class AiUpstreamErrors {

	private AiUpstreamErrors() {
	}

	/** 2xx 가 아닌 응답. 본문이 aiApiSpec §3 형식이면 그대로, 아니면 "비정상 응답" 이다. */
	static RuntimeException toException(HttpStatusCode status, HttpHeaders headers, String body, ObjectMapper mapper,
		AiProperties properties) {
		JsonNode node = parse(body, mapper);
		String requestId = node == null ? null : text(node, "request_id");
		if (status.value() == HttpStatus.UNAUTHORIZED.value() || status.value() == HttpStatus.FORBIDDEN.value()) {
			log.warn("AI 서버가 내부 토큰을 거절했다 status={} — finch.ai.internal-token 과 AI 의 BACKEND_SERVICE_TOKEN 을 대조한다",
				status);
			AiErrorCode code = AiErrorCode.AI_UPSTREAM_UNAVAILABLE;
			return new AiRelayException(code.getStatus(), code.getCode(), code.getMessage(),
				Map.of("reason", "upstream_auth"), requestId);
		}
		if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
			AiErrorCode code = AiErrorCode.AI_UPSTREAM_RATE_LIMITED;
			return new AiRelayException(code.getStatus(), code.getCode(), code.getMessage(), null, requestId,
				retryAfterSeconds(headers.getFirst(HttpHeaders.RETRY_AFTER), properties.rateLimitRetryAfter()));
		}
		if (node == null || !node.hasNonNull("code")) {
			log.warn("AI 서버가 에러 형식이 아닌 응답을 줬다 status={} body={}", status, abbreviate(body));
			return new CustomException(AiErrorCode.AI_UPSTREAM_UNAVAILABLE);
		}
		return passThrough(status, node);
	}

	static AiRelayException passThrough(HttpStatusCode status, JsonNode node) {
		JsonNode detail = node.get("detail");
		return new AiRelayException(status, node.get("code").asString(), text(node, "message"),
			detail == null || detail.isNull() ? null : CaseConverter.toCamel(detail), text(node, "request_id"));
	}

	/**
	 * AI 가 응답조차 하지 않은 경우. 타임아웃은 504, 나머지(연결 거부·DNS·본문 읽기 실패)는 502.
	 * <p>
	 * Reactor 의 {@code timeout()} 은 {@link TimeoutException} 을 내고 {@code block()} 이 그것을 {@code RuntimeException} 으로 감싼다.
	 * 커넥션 단계의 읽기 타임아웃({@code ReadTimeoutException})은 {@code WebClientRequestException} 의 cause 로 온다. 둘 다 504 다.
	 */
	static RuntimeException fromTransport(RuntimeException e, AiRoute route) {
		if (isTimeout(e)) {
			log.warn("AI 서버 응답 시간 초과 route={}", route);
			return new CustomException(AiErrorCode.AI_UPSTREAM_TIMEOUT);
		}
		log.warn("AI 서버 호출 실패 route={}: {}", route, e.toString());
		return new CustomException(AiErrorCode.AI_UPSTREAM_UNAVAILABLE);
	}

	private static boolean isTimeout(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof TimeoutException || t.getClass().getSimpleName().endsWith("TimeoutException")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code Retry-After} 를 초 단위 정수로. 초 값이면 그대로, HTTP 날짜면 지금부터의 초로 환산, 없거나 못 읽으면 기본값.
	 * <b>최소 1초</b> — 0 은 "즉시 재시도" 라 되풀이를 부른다 (apiSpec 10.4).
	 */
	static long retryAfterSeconds(String header, Duration fallback) {
		long seconds = fallback.toSeconds();
		if (header != null && !header.isBlank()) {
			String value = header.strip();
			try {
				seconds = Long.parseLong(value);
			} catch (NumberFormatException notSeconds) {
				try {
					Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
					seconds = Duration.between(Instant.now(), at).toSeconds();
				} catch (DateTimeParseException notDate) {
					log.warn("AI 서버의 Retry-After 를 읽지 못했다 — 기본값을 쓴다 value={}", value);
				}
			}
		}
		return Math.max(1, seconds);
	}

	static JsonNode parse(String body, ObjectMapper mapper) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonNode node = mapper.readTree(body);
			return node.isObject() ? node : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}

	private static String abbreviate(String body) {
		if (body == null) {
			return null;
		}
		return body.length() > 200 ? body.substring(0, 200) + "…" : body;
	}
}
