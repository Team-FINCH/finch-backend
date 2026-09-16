package com.finch.domain.ai.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.domain.ai.AiProperties;
import com.finch.domain.ai.exception.AiErrorCode;
import com.finch.global.exception.AiRelayException;
import com.finch.global.exception.CustomException;
import com.finch.global.security.InternalTokenFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 서버를 부르지 않고 응답만 흉내낸다 ({@code KakaoPayGatewayTest} 와 같은 방식). 스프링 없이 돈다.
 * <p>
 * 고정하는 것 — (1) 재포장: 봉투 보존 4종·제거 3종·중첩 camel·{@code ticker} 유지, 결과가 apiSpec 10.3 예시 모양 (2) 요청 camel→snake,
 * 헤더(내부 토큰·X-User-Id 덮어쓰기), 경로·쿼리 매핑 (3) 에러: AI 코드 통과 + requestId, 401/403 → 502, 429 는 상태 유지 + reason·message 보존,
 * 연결 실패 502, 타임아웃 504.
 */
class AiRelayServiceTest {

	private static final String ERROR_JSON = """
		{"code":"INSUFFICIENT_DATA","message":"분석에 필요한 데이터가 부족합니다","detail":{"missing_days":30},"request_id":"req_err_1"}""";

	private final JsonMapper mapper = JsonMapper.builder().build();
	private final List<ClientRequest> sent = new ArrayList<>();

	@Nested
	@DisplayName("성공 재포장 (apiSpec 10.3)")
	class Repackage {

		/** 완료 조건 — openapi.json 의 Envelope_AnalysisContent_ 모양 픽스처가 apiSpec 10.3 예시 모양이 되는지. */
		@Test
		@DisplayName("content 유지 + requestId·dataAsOf·citations·disclaimer 보존, generated_at·model·cached·freshness_warnings 제거, 키는 camel, ticker 는 그대로")
		void repackagesEnvelope() throws IOException {
			String envelope = fixture("ai/analysis-envelope.json");
			AiRelayService service = service(req -> json(HttpStatus.OK, envelope));

			ResponseEntity<JsonNode> res = service.relay(AiRoute.STOCK_ANALYSIS, Map.of("ticker", "005930"), null, 42L,
				mapper.readTree("{\"personalize\":true}"));

			assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
			JsonNode body = res.getBody();
			assertThat(body.propertyNames()).containsExactly("content", "requestId", "dataAsOf", "citations", "disclaimer");
			assertThat(body.get("requestId").asString()).isEqualTo("req_20260902_0001");
			assertThat(body.get("dataAsOf").get("price").asString()).isEqualTo("2026-09-02T14:29:50+09:00");
			assertThat(body.get("dataAsOf").get("macro").isNull()).isTrue();
			assertThat(body.get("citations").get(0).get("publishedAt").asString()).isEqualTo("2026-09-01T08:00:00+09:00");
			assertThat(body.get("disclaimer").asString()).isEqualTo("본 서비스는 모의투자이며 투자 자문이 아닙니다.");
			// content 안쪽 — 표기만 바뀌고 이름·값은 그대로다 (C74).
			JsonNode content = body.get("content");
			assertThat(content.get("ticker").asString()).isEqualTo("005930");
			assertThat(content.get("sections").get("riskLevel").asString()).isEqualTo("high");
			assertThat(content.get("sections").get("riskScore").asInt()).isEqualTo(72);
			assertThat(content.get("sections").get("summary").get("relatedTickers").get(0).asString()).isEqualTo("000660");
			assertThat(content.get("sections").get("findings").get(0).get("evidence").get("tickers").get(0).asString())
				.isEqualTo("005930");
			assertThat(content.get("sections").get("findings").get(0).get("thesisConflicts").get(0).get("ticker").asString())
				.isEqualTo("005930");
			assertThat(content.get("sections").get("findings").get(0).get("thesisConflicts").get(0).get("reasonCode").asString())
				.isEqualTo("valuation");
		}

		@Test
		@DisplayName("봉투가 아닌 응답(content 없음)은 camel 변환만 해서 그대로 넘긴다")
		void nonEnvelopePassesThrough() {
			AiRelayService service = service(req -> json(HttpStatus.OK, "{\"deleted_fact_id\":\"f1\"}"));

			JsonNode body = service.relay(AiRoute.WIKI_FACT_DELETE, Map.of("factId", "f1"), null, 42L, null).getBody();

			assertThat(body.get("deletedFactId").asString()).isEqualTo("f1");
		}

		/**
		 * 이슈 #79 — 대화 이력도 봉투다(AI openapi.json {@code Envelope[ChatHistoryContent]}). 메시지 한 줄의 {@code content} 는 이름만 같은
		 * 필드라 봉투로 오인되면 안 된다 — 재포장은 최상위 {@code content} 만 본다.
		 */
		@Test
		@DisplayName("대화 이력 — 봉투 재포장, conversationId·messages 는 content 아래, 메시지의 content 는 문자열 그대로, created_at 은 createdAt")
		void repackagesConversationHistory() {
			AiRelayService service = service(req -> json(HttpStatus.OK, """
				{"request_id":"req_h1","generated_at":"2026-09-15T14:00:02+09:00",
				 "data_as_of":{"price":null,"portfolio":null,"filings":null,"news":null,"macro":null},
				 "model":"m","cached":false,
				 "content":{"conversation_id":"conv_01","messages":[
				   {"role":"user","content":"내 삼성전자 비중은?","created_at":"2026-09-15T14:00:00+09:00"},
				   {"role":"assistant","content":"삼성전자는 포트폴리오의 …","created_at":"2026-09-15T14:00:01+09:00"}]},
				 "citations":[],"freshness_warnings":[],"disclaimer":"면책"}"""));

			JsonNode body = service.relay(AiRoute.CHAT_CONVERSATION_MESSAGES, Map.of("conversationId", "conv_01"), null, 42L,
				null).getBody();

			ClientRequest req = sent.getFirst();
			assertThat(req.method()).isEqualTo(HttpMethod.GET);
			assertThat(req.url()).isEqualTo(URI.create("https://ai.test/api/ai/v1/chat/conversations/conv_01/messages"));
			assertThat(req.headers().getContentType()).isNull();
			assertThat(body.propertyNames()).containsExactly("content", "requestId", "dataAsOf", "citations", "disclaimer");
			JsonNode content = body.get("content");
			assertThat(content.get("conversationId").asString()).isEqualTo("conv_01");
			assertThat(content.get("messages").get(0).get("role").asString()).isEqualTo("user");
			assertThat(content.get("messages").get(1).get("content").asString()).isEqualTo("삼성전자는 포트폴리오의 …");
			assertThat(content.get("messages").get(0).get("createdAt").asString()).isEqualTo("2026-09-15T14:00:00+09:00");
		}

		@Test
		@DisplayName("위키 확정 — 본문 없이 POST /wiki/facts/{factId}/confirm 으로 간다")
		void confirmFactGoesToConfirmPath() {
			AiRelayService service = service(req -> json(HttpStatus.OK, "{\"content\":{\"id\":\"f2\",\"source\":\"user_stated\"}}"));

			JsonNode body = service.relay(AiRoute.WIKI_FACT_CONFIRM, Map.of("factId", "f2"), null, 42L, null).getBody();

			ClientRequest req = sent.getFirst();
			assertThat(req.method()).isEqualTo(HttpMethod.POST);
			assertThat(req.url()).isEqualTo(URI.create("https://ai.test/api/ai/v1/wiki/facts/f2/confirm"));
			assertThat(req.headers().getContentType()).isNull();
			assertThat(body.get("content").get("source").asString()).isEqualTo("user_stated");
		}
	}

	@Nested
	@DisplayName("요청")
	class Request {

		@Test
		@DisplayName("본문 키를 snake 로 바꾸고, 내부 토큰과 토큰 사용자의 X-User-Id 를 싣고, 경로 변수를 채운다")
		void buildsUpstreamRequest() throws IOException {
			AiRelayService service = service(req -> json(HttpStatus.OK, "{\"content\":{}}"));

			service.relay(AiRoute.WIKI_THESIS_UPDATE, Map.of("ticker", "005930"), null, 42L,
				mapper.readTree("{\"thesisText\":\"x\",\"targetPrice\":80000,\"linkedTradeId\":7}"));

			ClientRequest req = sent.getFirst();
			assertThat(req.method()).isEqualTo(HttpMethod.PUT);
			assertThat(req.url()).isEqualTo(URI.create("https://ai.test/api/ai/v1/wiki/theses/005930"));
			assertThat(req.headers().getFirst(InternalTokenFilter.HEADER)).isEqualTo("ai-token");
			assertThat(req.headers().getFirst("X-User-Id")).isEqualTo("42");
			assertThat(req.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
			assertThat(bodyOf(req)).contains("\"thesis_text\"").contains("\"target_price\"").contains("\"linked_trade_id\"")
				.doesNotContain("thesisText");
		}

		/**
		 * v0.8.19 (이슈 #90 ㄱ). 우리가 프론트에서 받는 헤더 이름은 {@code Idempotency-Key} 지만 AI 로는
		 * {@code X-Idempotency-Key} 로 나간다 — 두 이름이 뒤바뀌면 AI 는 키 없는 요청으로 보고 매번 새 작업을 만든다.
		 */
		@Test
		@DisplayName("POST /chat/jobs — 멱등성 키를 X-Idempotency-Key 로 싣는다")
		void sendsIdempotencyKey() throws IOException {
			AiRelayService service = service(req -> json(HttpStatus.ACCEPTED, "{\"content\":{\"job_id\":\"job_1\"}}"));

			service.relay(AiRoute.CHAT_JOB_CREATE, null, null, 42L, mapper.readTree("{\"question\":\"삼성전자?\"}"),
				"11111111-2222-3333-4444-555555555555");

			ClientRequest req = sent.getFirst();
			assertThat(req.url()).isEqualTo(URI.create("https://ai.test/api/ai/v1/chat/jobs"));
			assertThat(req.headers().getFirst(AiRelayService.IDEMPOTENCY_HEADER))
				.isEqualTo("11111111-2222-3333-4444-555555555555");
			assertThat(req.headers().getFirst("X-User-Id")).isEqualTo("42");
		}

		/**
		 * 키가 없으면 <b>헤더 자리를 비워 둔다.</b> 빈 문자열을 실어 보내면 AI 가 "키 있음" 으로 읽어 서로 다른 질문이
		 * 한 작업으로 합쳐질 수 있다 (AI api-spec §4.2 — 헤더가 없으면 매번 새 작업).
		 */
		@Test
		@DisplayName("멱등성 키가 null 이거나 비어 있으면 헤더를 싣지 않는다")
		void omitsBlankIdempotencyKey() throws IOException {
			AiRelayService service = service(req -> json(HttpStatus.ACCEPTED, "{\"content\":{}}"));

			service.relay(AiRoute.CHAT_JOB_CREATE, null, null, 42L, mapper.readTree("{}"), null);
			service.relay(AiRoute.CHAT_JOB_CREATE, null, null, 42L, mapper.readTree("{}"), "   ");

			assertThat(sent).hasSize(2)
				.allSatisfy(req -> assertThat(req.headers().getFirst(AiRelayService.IDEMPOTENCY_HEADER)).isNull());
		}

		/** 202 는 2xx 라 에러가 아니다 — 상태 코드를 그대로 내려보내고 봉투만 재포장한다. */
		@Test
		@DisplayName("GET /chat/jobs/{jobId} — 경로 변수를 채우고 202·200 을 그대로 돌려준다")
		void chatJobStatus() {
			AiRelayService service = service(req -> json(HttpStatus.OK,
				"{\"content\":{\"job_id\":\"job_1\",\"status\":\"completed\",\"completed_at\":\"2026-09-16T14:30:42+09:00\"}}"));

			ResponseEntity<JsonNode> res = service.relay(AiRoute.CHAT_JOB_STATUS, Map.of("jobId", "job_1"), null, 42L, null);

			assertThat(sent.getFirst().url()).isEqualTo(URI.create("https://ai.test/api/ai/v1/chat/jobs/job_1"));
			assertThat(sent.getFirst().method()).isEqualTo(HttpMethod.GET);
			assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(res.getBody().get("content").get("jobId").asString()).isEqualTo("job_1");
			assertThat(res.getBody().get("content").get("completedAt").asString())
				.isEqualTo("2026-09-16T14:30:42+09:00");
		}

		/** v0.8.8 에서 들어온 경로. 경로 변수가 없는 POST 도 같은 변환(camel → snake)을 탄다. */
		@Test
		@DisplayName("POST /wiki/theses — 경로 변수 없이 AI 의 /wiki/theses 로 가고 linkedTradeId 가 linked_trade_id 가 된다")
		void createThesisGoesToWikiTheses() throws IOException {
			AiRelayService service = service(req -> json(HttpStatus.OK, "{\"content\":{}}"));

			service.relay(AiRoute.WIKI_THESIS_CREATE, null, null, 42L,
				mapper.readTree("{\"ticker\":\"000660\",\"text\":\"HBM\",\"horizon\":\"long\",\"linkedTradeId\":\"101\"}"));

			ClientRequest req = sent.getFirst();
			assertThat(req.method()).isEqualTo(HttpMethod.POST);
			assertThat(req.url()).isEqualTo(URI.create("https://ai.test/api/ai/v1/wiki/theses"));
			assertThat(req.headers().getFirst("X-User-Id")).isEqualTo("42");
			assertThat(bodyOf(req)).contains("\"ticker\":\"000660\"").contains("\"linked_trade_id\":\"101\"")
				.doesNotContain("linkedTradeId");
		}

		@Test
		@DisplayName("GET 은 본문 없이 쿼리를 그대로 넘긴다 (briefing 의 date)")
		void forwardsQuery() {
			AiRelayService service = service(req -> json(HttpStatus.OK, "{\"content\":{}}"));
			LinkedMultiValueMap<String, String> query = new LinkedMultiValueMap<>();
			query.add("date", "2026-09-07");

			service.relay(AiRoute.BRIEFING, null, query, 42L, null);

			ClientRequest req = sent.getFirst();
			assertThat(req.method()).isEqualTo(HttpMethod.GET);
			assertThat(req.url().toString()).isEqualTo("https://ai.test/api/ai/v1/briefing?date=2026-09-07");
			assertThat(req.headers().getContentType()).isNull();
		}
	}

	@Nested
	@DisplayName("에러 (apiSpec 10.4)")
	class Errors {

		@Test
		@DisplayName("AI 가 응답한 에러는 상태·code·message·detail 그대로 통과하고 request_id 는 requestId 로 보존된다")
		void passesThroughAiError() {
			AiRelayService service = service(req -> json(HttpStatus.CONFLICT, ERROR_JSON));

			assertThatThrownBy(() -> service.relay(AiRoute.PORTFOLIO_DIAGNOSIS, null, null, 42L, null))
				.isInstanceOf(AiRelayException.class)
				.satisfies(e -> {
					AiRelayException ex = (AiRelayException) e;
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_DATA");
					assertThat(ex.getMessage()).isEqualTo("분석에 필요한 데이터가 부족합니다");
					assertThat(((JsonNode) ex.getDetail()).get("missingDays").asInt()).isEqualTo(30);
					assertThat(ex.getRequestId()).isEqualTo("req_err_1");
					assertThat(ex.getRetryAfterSeconds()).isNull();
				});
		}

		@Test
		@DisplayName("401·403 은 502 AI_UPSTREAM_UNAVAILABLE 로 재포장되고 detail.reason=upstream_auth, requestId 는 보존, AI 의 detail 은 버린다")
		void repackagesAuthErrors() {
			for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)) {
				AiRelayService service = service(req -> json(status, """
					{"code":"UNAUTHORIZED","message":"인증 실패","detail":{"reason":"internal_token_mismatch"},"request_id":"req_auth"}"""));

				assertThatThrownBy(() -> service.relay(AiRoute.CHAT, null, null, 42L, null))
					.isInstanceOf(AiRelayException.class)
					.satisfies(e -> {
						AiRelayException ex = (AiRelayException) e;
						assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
						assertThat(ex.getCode()).isEqualTo("AI_UPSTREAM_UNAVAILABLE");
						assertThat(ex.getMessage()).isEqualTo(AiErrorCode.AI_UPSTREAM_UNAVAILABLE.getMessage());
						assertThat(ex.getDetail()).isEqualTo(Map.of("reason", "upstream_auth"));
						assertThat(ex.getRequestId()).isEqualTo("req_auth");
					});
			}
		}

		@Test
		@DisplayName("429 는 상태 그대로 AI_UPSTREAM_RATE_LIMITED 이고 Retry-After 는 AI 값(초·날짜), 최소 1초다")
		void keepsRateLimitStatus() {
			AiRelayException fromSeconds = rateLimited("12", "{\"code\":\"RATE_LIMITED\",\"message\":\"한도\","
				+ "\"detail\":{\"reason\":\"request_rate_limit\"},\"request_id\":\"req_rl\"}");
			assertThat(fromSeconds.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
			assertThat(fromSeconds.getCode()).isEqualTo("AI_UPSTREAM_RATE_LIMITED");
			assertThat(fromSeconds.getRetryAfterSeconds()).isEqualTo(12L);
			assertThat(fromSeconds.getRequestId()).isEqualTo("req_rl");

			String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusSeconds(30));
			assertThat(rateLimited(date, RATE_LIMIT_BODY).getRetryAfterSeconds()).isBetween(28L, 30L);
			assertThat(rateLimited("0", RATE_LIMIT_BODY).getRetryAfterSeconds()).isEqualTo(1L);
		}

		@Test
		@DisplayName("Retry-After 는 AI 가 준 경우에만 실린다 — 없거나 못 읽으면 기본값을 지어내지 않는다")
		void omitsRetryAfterWhenUpstreamGivesNone() {
			// daily_token_budget 은 자정까지 풀리지 않아 AI 가 헤더를 주지 않는다. 5초를 지어내면 프론트가 5초 뒤 또 막힌다.
			assertThat(rateLimited(null, RATE_LIMIT_BODY).getRetryAfterSeconds()).isNull();
			assertThat(rateLimited("garbage", RATE_LIMIT_BODY).getRetryAfterSeconds()).isNull();
		}

		@Test
		@DisplayName("429 는 detail.reason 과 AI 의 message 를 보존하고 reason 외의 키는 버린다")
		void keepsRateLimitReasonAndMessage() {
			AiRelayException budget = rateLimited(null, "{\"code\":\"RATE_LIMITED\","
				+ "\"message\":\"오늘 사용할 수 있는 AI 분석량을 모두 사용했습니다.\","
				+ "\"detail\":{\"reason\":\"daily_token_budget\",\"used_tokens\":500000,\"limit_tokens\":500000},"
				+ "\"request_id\":\"req_budget\"}");
			assertThat(budget.getDetail()).isEqualTo(Map.of("reason", "daily_token_budget"));
			assertThat(budget.getMessage()).isEqualTo("오늘 사용할 수 있는 AI 분석량을 모두 사용했습니다.");

			AiRelayException rate = rateLimited("12", RATE_LIMIT_BODY);
			assertThat(rate.getDetail()).isEqualTo(Map.of("reason", "request_rate_limit"));

			// reason 이 없으면 detail 자체를 싣지 않는다. message 가 없으면 백엔드 기본 문구로 채운다.
			AiRelayException bare = rateLimited("3", "{\"code\":\"RATE_LIMITED\",\"request_id\":\"req_bare\"}");
			assertThat(bare.getDetail()).isNull();
			assertThat(bare.getMessage()).isEqualTo(AiErrorCode.AI_UPSTREAM_RATE_LIMITED.getMessage());
		}

		@Test
		@DisplayName("에러 형식이 아닌 5xx 본문(HTML 등)은 502 AI_UPSTREAM_UNAVAILABLE 이고 requestId 가 없다")
		void malformedErrorBody() {
			AiRelayService service = service(req -> ClientResponse.create(HttpStatus.BAD_GATEWAY)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE).body("<html>bad gateway</html>").build());

			assertThatThrownBy(() -> service.relay(AiRoute.CHAT, null, null, 42L, null))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(AiErrorCode.AI_UPSTREAM_UNAVAILABLE);
		}

		@Test
		@DisplayName("연결 실패는 502 AI_UPSTREAM_UNAVAILABLE")
		void connectionFailure() {
			AiRelayService service = new AiRelayService(WebClient.builder().exchangeFunction(req ->
				Mono.error(new WebClientRequestException(new java.net.ConnectException("refused"), req.method(), req.url(),
					req.headers()))), properties(Duration.ofSeconds(5)));

			assertThatThrownBy(() -> service.relay(AiRoute.CHAT, null, null, 42L, null))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(AiErrorCode.AI_UPSTREAM_UNAVAILABLE);
		}

		@Test
		@DisplayName("타임아웃은 504 AI_UPSTREAM_TIMEOUT")
		void timeout() {
			AiRelayService service = new AiRelayService(WebClient.builder().exchangeFunction(req -> Mono.never()),
				properties(Duration.ofMillis(200)));

			assertThatThrownBy(() -> service.relay(AiRoute.CHAT, null, null, 42L, null))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(AiErrorCode.AI_UPSTREAM_TIMEOUT);
		}

		/**
		 * 경로마다 다른 제한을 쓴다 (이슈 #90). 긴 쪽을 아주 길게, 짧은 쪽을 아주 짧게 두고 <b>짧은 경로만</b>
		 * 끊기는 것으로 확인한다 — 두 값이 뒤바뀌면 이 테스트에서 채팅 작업 조회가 살아남고 동기 채팅이 끊긴다.
		 */
		@Test
		@DisplayName("채팅 작업 경로는 quick-timeout 을, 나머지는 timeout 을 쓴다")
		void quickRoutesUseTheirOwnTimeout() {
			AiRelayService service = new AiRelayService(WebClient.builder().exchangeFunction(req -> Mono.never()),
				new AiProperties("https://ai.test", "ai-token", Duration.ofSeconds(30), Duration.ofMillis(200)));

			assertThatThrownBy(() -> service.relay(AiRoute.CHAT_JOB_STATUS, Map.of("jobId", "job_1"), null, 42L, null))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(AiErrorCode.AI_UPSTREAM_TIMEOUT);
		}
	}

	// ---- helpers ----

	private static final String RATE_LIMIT_BODY = "{\"code\":\"RATE_LIMITED\",\"message\":\"요청이 너무 많습니다.\","
		+ "\"detail\":{\"reason\":\"request_rate_limit\",\"endpoint\":\"stocks.analysis\"},\"request_id\":\"req_rl\"}";

	/** 429 응답 하나를 흘려보내고 나온 예외를 준다. {@code header} 가 {@code null} 이면 {@code Retry-After} 를 붙이지 않는다. */
	private AiRelayException rateLimited(String header, String body) {
		AiRelayService service = service(req -> {
			ClientResponse.Builder b = ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(body);
			if (header != null) {
				b.header(HttpHeaders.RETRY_AFTER, header);
			}
			return b.build();
		});
		try {
			service.relay(AiRoute.CHAT, null, null, 42L, null);
			throw new AssertionError("예외가 나야 한다");
		} catch (AiRelayException e) {
			return e;
		}
	}

	private AiRelayService service(Function<ClientRequest, ClientResponse> responder) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			sent.add(request);
			return Mono.just(responder.apply(request));
		});
		return new AiRelayService(builder, properties(Duration.ofSeconds(5)));
	}

	/** quick 쪽도 같은 값으로 둔다 — 두 제한을 가르는 것은 {@code quickRoutesUseTheirOwnTimeout} 하나가 본다. */
	private static AiProperties properties(Duration timeout) {
		return new AiProperties("https://ai.test", "ai-token", timeout, timeout);
	}

	private static ClientResponse json(HttpStatus status, String body) {
		return ClientResponse.create(status)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.body(body)
			.build();
	}

	private static String fixture(String path) throws IOException {
		return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}

	/** 스텁 exchange 는 본문을 흘려보내지 않으므로 요청의 BodyInserter 를 직접 실행해 바이트를 꺼낸다. */
	private static String bodyOf(ClientRequest request) {
		org.springframework.mock.http.client.reactive.MockClientHttpRequest mock =
			new org.springframework.mock.http.client.reactive.MockClientHttpRequest(request.method(), request.url());
		request.writeTo(mock, org.springframework.web.reactive.function.client.ExchangeStrategies.withDefaults()).block();
		return mock.getBodyAsString().block();
	}
}
