package com.finch.domain.ai.relay;

import com.finch.domain.ai.AiProperties;
import com.finch.domain.ai.exception.AiErrorCode;
import com.finch.global.exception.CustomException;
import com.finch.global.security.InternalTokenFilter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 프론트의 {@code /api/v1/ai/**} 를 AI 서버의 {@code /api/ai/v1/**} 로 넘기고 응답을 백엔드 형식으로 재포장한다 (apiSpec 10장).
 * <p>
 * <b>제네릭 프록시다.</b> 엔드포인트가 무엇이든 하는 일이 같다 — (1) 요청 본문 키를 camel → snake, (2) {@code X-Internal-Token} 과
 * <b>토큰의 사용자로 새로 쓴 {@code X-User-Id}</b> 부착(클라이언트가 보낸 값은 버린다, aiApiSpec §4), (3) 응답 봉투에서
 * {@code content} 와 보존 4종({@code request_id}·{@code data_as_of}·{@code citations}·{@code disclaimer})만 남기고 키를 snake → camel.
 * {@code generated_at}·{@code model}·{@code cached} 등 나머지 봉투 필드는 걷어낸다 (apiSpec 10.3, contracts C7).
 * <p>
 * 트랜잭션이 없다 — 외부 HTTP 이고 DB 를 보지 않는다. 대기는 경로에 따라 갈린다 ({@link AiProperties#timeoutFor}): LLM 을
 * 기다리는 것은 60초, 채팅 작업 생성·조회는 10초이고 그동안 서블릿 스레드를 잡는다. 스트리밍은 폐기됐다 (aiApiSpec §1.2).
 */
@Slf4j
@Service
public class AiRelayService {

	static final String USER_HEADER = "X-User-Id";
	/** AI 쪽 멱등성 헤더 이름. 우리가 프론트에서 받는 이름({@code Idempotency-Key})과 다르다 (AI openapi.json). */
	static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
	/** 봉투에서 {@code content} 옆에 남기는 것. 순서가 곧 응답 순서다 (apiSpec 10.3 예시). */
	private static final List<String> PRESERVED = List.of("request_id", "data_as_of", "citations", "disclaimer");

	private final WebClient webClient;
	private final AiProperties properties;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	public AiRelayService(WebClient.Builder builder, AiProperties properties) {
		this.webClient = builder.baseUrl(properties.baseUrl()).build();
		this.properties = properties;
	}

	/**
	 * 한 번 중계한다.
	 *
	 * @param route     AI 쪽 경로.
	 * @param pathVars  경로 변수 ({@code ticker}·{@code factId}). 우리 경로의 {@code stockCode} 값을 {@code ticker} 자리에 넣는다.
	 * @param query     쿼리 파라미터 ({@code briefing} 의 {@code date}). 그대로 넘긴다.
	 * @param body      프론트가 보낸 camelCase 본문. null 이면 본문 없이 보낸다.
	 * @return AI 가 준 상태 코드 그대로(대개 200)와 재포장한 본문.
	 */
	public ResponseEntity<JsonNode> relay(AiRoute route, Map<String, ?> pathVars, MultiValueMap<String, String> query,
		long userId, JsonNode body) {
		return relay(route, pathVars, query, userId, body, null);
	}

	/**
	 * 멱등성 키를 함께 실어 중계한다. 채팅 작업 생성({@link AiRoute#CHAT_JOB_CREATE})만 쓴다.
	 * <p>
	 * <b>키가 여기까지 오는 경우는 이미 걸러진 뒤다.</b> 같은 키의 재전송은 {@code IdempotencyFilter} 가 붙잡아 최초 응답을
	 * 그대로 재생하므로 컨트롤러에 닿지 않는다. 그런데도 AI 에 키를 넘기는 것은 <b>그 필터가 Redis 에 얹혀 있기</b>
	 * 때문이다 — 키가 만료되거나 Redis 가 비면 필터는 같은 요청을 "처음 보는 것" 으로 판정하고, 그때 AI 쪽 장부가
	 * 같은 {@code job_id} 를 202 로 돌려주어 답을 두 번 만들지 않는다 (AI api-spec §4.2, 이슈 #90 ㄱ).
	 * <p>
	 * 키가 {@code null} 이면 헤더를 싣지 않는다. AI 는 그 경우 매번 새 작업을 만든다 — 지어낸 값을 보내는 것보다
	 * 없는 것이 낫다. 빈 문자열도 같이 걸러낸다: 헤더 자리는 있는데 값이 없는 요청을 AI 가 "키 있음" 으로 읽으면
	 * 서로 다른 질문이 한 작업으로 합쳐진다.
	 */
	public ResponseEntity<JsonNode> relay(AiRoute route, Map<String, ?> pathVars, MultiValueMap<String, String> query,
		long userId, JsonNode body, String idempotencyKey) {
		Upstream upstream = exchange(route, pathVars, query, userId, body, idempotencyKey);
		if (!upstream.status().is2xxSuccessful()) {
			throw AiUpstreamErrors.toException(upstream.status(), upstream.headers(), upstream.body(), objectMapper);
		}
		return ResponseEntity.status(upstream.status()).body(repackage(upstream.body()));
	}

	private Upstream exchange(AiRoute route, Map<String, ?> pathVars, MultiValueMap<String, String> query, long userId,
		JsonNode body, String idempotencyKey) {
		WebClient.RequestBodySpec spec = webClient.method(route.method())
			.uri(builder -> buildUri(builder, route, pathVars, query))
			.header(InternalTokenFilter.HEADER, properties.internalToken())
			// 클라이언트가 보낸 X-User-Id 는 여기 닿지 않는다 — 컨트롤러가 읽지 않는다. 토큰의 사용자만 싣는다.
			.header(USER_HEADER, String.valueOf(userId))
			.accept(MediaType.APPLICATION_JSON);
		if (StringUtils.hasText(idempotencyKey)) {
			spec = spec.header(IDEMPOTENCY_HEADER, idempotencyKey);
		}
		WebClient.RequestHeadersSpec<?> request = body == null ? spec
			: spec.contentType(MediaType.APPLICATION_JSON).bodyValue(CaseConverter.toSnake(body).toString());
		try {
			Upstream upstream = request
				.exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
					.map(text -> new Upstream(response.statusCode(), response.headers().asHttpHeaders(), text)))
				.timeout(properties.timeoutFor(route))
				.block();
			if (upstream == null) {
				throw new CustomException(AiErrorCode.AI_UPSTREAM_UNAVAILABLE);
			}
			return upstream;
		} catch (CustomException e) {
			throw e;
		} catch (RuntimeException e) {
			throw AiUpstreamErrors.fromTransport(e, route);
		}
	}

	private static URI buildUri(UriBuilder builder, AiRoute route, Map<String, ?> pathVars,
		MultiValueMap<String, String> query) {
		builder.path(route.upstreamTemplate());
		if (query != null) {
			builder.queryParams(query);
		}
		return builder.build(pathVars == null ? Map.of() : pathVars);
	}

	/**
	 * 봉투 → 백엔드 형식. {@code content} 가 없는 응답(봉투가 아닌 것)은 그대로 camel 변환만 해서 넘긴다 — 우리가 모르는 모양을
	 * 억지로 봉투로 만들지 않는다.
	 */
	JsonNode repackage(String text) {
		JsonNode envelope;
		try {
			envelope = text == null || text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
		} catch (RuntimeException e) {
			throw new CustomException(AiErrorCode.AI_UPSTREAM_UNAVAILABLE);
		}
		if (!envelope.isObject() || !envelope.has("content")) {
			return CaseConverter.toCamel(envelope);
		}
		ObjectNode out = objectMapper.createObjectNode();
		out.set("content", CaseConverter.toCamel(envelope.get("content")));
		for (String key : PRESERVED) {
			if (envelope.has(key)) {
				out.set(CaseConverter.camel(key), CaseConverter.toCamel(envelope.get(key)));
			}
		}
		return out;
	}

	record Upstream(HttpStatusCode status, HttpHeaders headers, String body) {
	}
}
