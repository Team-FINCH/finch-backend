package com.finch.domain.ai.relay;

import com.finch.domain.ai.AiProperties;
import com.finch.domain.ai.exception.AiErrorCode;
import com.finch.global.exception.AiRelayException;
import com.finch.global.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 서버의 에러를 프론트로 어떻게 넘기나 (apiSpec 10.4). 규칙은 하나다 — <b>AI 가 응답한 에러는 code·message·detail·상태를 그대로
 * 통과</b>시키고 {@code request_id} 를 {@code requestId} 로 보존한다. 백엔드가 자기 5xx 로 뭉개면 프론트가 AI 위젯만 접고 시세·주문을
 * 살리는 에러 경계를 만들 수 없다.
 * <p>
 * 이 커밋의 범위는 통과뿐이다. 401·403·429 재포장과 연결 실패·타임아웃은 다음 커밋이 붙인다.
 */
@Slf4j
final class AiUpstreamErrors {

	private AiUpstreamErrors() {
	}

	/** 2xx 가 아닌 응답. 본문이 aiApiSpec §3 형식이면 그대로, 아니면 "비정상 응답" 이다. */
	static RuntimeException toException(HttpStatusCode status, HttpHeaders headers, String body, ObjectMapper mapper,
		AiProperties properties) {
		JsonNode node = parse(body, mapper);
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

	/** 연결 실패·타임아웃 등 AI 가 응답조차 하지 않은 경우. 이 커밋에서는 전부 502 다. */
	static RuntimeException fromTransport(RuntimeException e, AiRoute route) {
		log.warn("AI 서버 호출 실패 route={}: {}", route, e.toString());
		return new CustomException(AiErrorCode.AI_UPSTREAM_UNAVAILABLE);
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
