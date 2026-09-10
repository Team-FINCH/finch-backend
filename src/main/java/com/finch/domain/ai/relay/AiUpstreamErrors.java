package com.finch.domain.ai.relay;

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
 * 예외가 둘이다.
 * <ul>
 *   <li><b>401·403 → 502 {@code AI_UPSTREAM_UNAVAILABLE}</b>, {@code detail.reason=upstream_auth} (v0.8). 그대로 흘리면 프론트
 *       인증 인터셉터가 사용자 토큰 만료로 오인해 로그아웃시킨다. 실제 원인은 백엔드↔AI 의 내부 토큰이고 사용자와 무관하다.
 *       <b>AI 가 준 {@code detail} 은 버린다</b> — 내부 인증 실패 메시지에는 사용자에게 보여줄 것이 없다.</li>
 *   <li><b>429 는 상태를 그대로 두고 {@code code} 만 {@code AI_UPSTREAM_RATE_LIMITED} 로 바꾼다</b> (v0.8.6). AI 다리에서 온
 *       한도임을 표시할 뿐이다. v0.8 은 이것을 503 으로 바꿨는데 그 근거("백엔드 전체의 호출량이 상한에 닿은 것")가 사실과
 *       달랐다 — AI 의 한도는 {@code (user_id, endpoint)} 단위라 429 의 뜻 그대로다.</li>
 * </ul>
 * 429 에서는 AI 가 준 것을 최대한 살린다. {@code detail.reason} 은 <b>분당 횟수 한도({@code request_rate_limit})와 그날의
 * 사용량 소진({@code daily_token_budget})을 가르는 유일한 값</b>이고 둘은 풀리는 시점이 다르다 — 앞은 {@code Retry-After} 초
 * 뒤, 뒤는 자정이다. {@code message} 도 AI 것이 정확하므로 그대로 옮긴다. 다만 {@code reason} 외의 키
 * ({@code endpoint}·{@code used_tokens}·{@code limit_tokens})는 내부 값이라 옮기지 않는다.
 * <p>
 * {@code Retry-After} 는 <b>AI 가 준 경우에만</b> 싣는다. AI 는 {@code daily_token_budget} 일 때 헤더를 주지 않는데, 그 자리를
 * 기본값으로 채우면 자정까지 풀리지 않을 요청을 몇 초 뒤에 다시 보내라고 말하는 것이 된다. 없으면 프론트가 자체 백오프로
 * 판단한다. {@code requestId} 는 세 경우 모두 있으면 보존한다 (contracts C70).
 * <p>
 * AI 가 응답조차 하지 않은 경우는 백엔드 자체 코드다 — 연결 실패 502 {@code AI_UPSTREAM_UNAVAILABLE}, 타임아웃 504
 * {@code AI_UPSTREAM_TIMEOUT}. 둘 다 {@code requestId} 가 없다.
 */
@Slf4j
final class AiUpstreamErrors {

	private AiUpstreamErrors() {
	}

	/** 2xx 가 아닌 응답. 본문이 aiApiSpec §3 형식이면 그대로, 아니면 "비정상 응답" 이다. */
	static RuntimeException toException(HttpStatusCode status, HttpHeaders headers, String body, ObjectMapper mapper) {
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
			String reason = reason(node);
			return new AiRelayException(code.getStatus(), code.getCode(), message(node, code),
				reason == null ? null : Map.of("reason", reason), requestId,
				retryAfterSeconds(headers.getFirst(HttpHeaders.RETRY_AFTER)));
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
	 * {@code Retry-After} 를 초 단위 정수로. 초 값이면 그대로, HTTP 날짜면 지금부터의 초로 환산.
	 * <p>
	 * <b>없거나 못 읽으면 {@code null} 이고 헤더를 싣지 않는다</b> (apiSpec 10.4, v0.8.6). 기본값을 지어내면 자정까지 풀리지
	 * 않는 {@code daily_token_budget} 에도 "몇 초 뒤 다시" 를 말하게 된다. <b>최소 1초</b> — 0 은 "즉시 재시도" 라 되풀이를 부른다.
	 */
	static Long retryAfterSeconds(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		String value = header.strip();
		long seconds;
		try {
			seconds = Long.parseLong(value);
		} catch (NumberFormatException notSeconds) {
			try {
				Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
				seconds = Duration.between(Instant.now(), at).toSeconds();
			} catch (DateTimeParseException notDate) {
				log.warn("AI 서버의 Retry-After 를 읽지 못했다 — 헤더를 싣지 않는다 value={}", value);
				return null;
			}
		}
		return Math.max(1, seconds);
	}

	/**
	 * 429 의 {@code detail.reason}. 프론트가 분당 횟수 한도({@code request_rate_limit})와 그날의 사용량 소진
	 * ({@code daily_token_budget})을 가르는 유일한 값이다. 나머지 키는 내부 값이라 옮기지 않는다 (apiSpec 10.4).
	 */
	private static String reason(JsonNode node) {
		JsonNode detail = node == null ? null : node.get("detail");
		return detail == null || !detail.isObject() ? null : text(detail, "reason");
	}

	/** 429 의 문구는 AI 것이 정확하다 — 두 한도의 문구가 이미 다르다. 없을 때만 백엔드 기본 문구로 채운다. */
	private static String message(JsonNode node, AiErrorCode fallback) {
		String message = node == null ? null : text(node, "message");
		return message == null || message.isBlank() ? fallback.getMessage() : message;
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
