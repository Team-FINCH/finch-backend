package com.finch.domain.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.ai.exception.AiErrorCode;
import com.finch.domain.ai.relay.AiRelayController;
import com.finch.domain.ai.relay.AiRelayService;
import com.finch.domain.ai.relay.AiRoute;
import com.finch.domain.ai.service.WikiThesisService;
import com.finch.global.config.SecurityConfig;
import com.finch.global.exception.AiRelayException;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import com.finch.global.util.StockUniverse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 중계 컨트롤러의 경로 11종이 JWT 로 보호되고 서비스에 올바른 라우트·경로 변수·본문을 넘기는지, 그리고 서비스가 던진 예외가
 * 계약대로 나가는지(상태·code·requestId·Retry-After). 재포장·에러 규칙 자체는 {@code AiRelayServiceTest} 가 본다.
 */
@WebMvcTest(AiRelayController.class)
@Import(SecurityConfig.class)
class AiRelayControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AiRelayService relayService;

	/** 논지를 쓰는 두 경로(POST·PUT)는 이 서비스를 지난다 — 중계 뒤에 알림함의 논지 캐시를 지운다. */
	@MockitoBean
	private WikiThesisService wikiThesisService;

	@MockitoBean
	private JwtProvider jwtProvider;

	/** 종목 범위는 기본으로 전부 통과시킨다. 범위 밖 판정은 아래 {@link #analysisOutsideUniverse} 하나가 본다. */
	@MockitoBean
	private StockUniverse universe;

	@BeforeEach
	void universePassesEverything() {
		given(universe.contains(anyString())).willReturn(true);
	}

	@Test
	@DisplayName("POST /ai/stocks/{stockCode}/analysis — 종목 범위 밖이면 STOCK_NOT_FOUND 404 이고 AI 서버로 가지 않는다")
	void analysisOutsideUniverse() throws Exception {
		givenLoggedIn(42L);
		given(universe.contains("000150")).willReturn(false);

		mockMvc.perform(authed(post("/api/v1/ai/stocks/000150/analysis")).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
		verifyNoInteractions(relayService);
	}

	@Test
	@DisplayName("POST /ai/stocks/{stockCode}/analysis — 경로 변수를 ticker 로, 본문을 그대로, 사용자는 토큰에서 넘긴다. 클라이언트 X-User-Id 는 읽지 않는다")
	void analysis() throws Exception {
		givenLoggedIn(42L);
		given(relayService.relay(eq(AiRoute.STOCK_ANALYSIS), eq(Map.of("ticker", "005930")), isNull(), eq(42L), any()))
			.willReturn(ResponseEntity.ok(mapper.readTree("{\"content\":{\"ticker\":\"005930\"},\"requestId\":\"r1\"}")));

		mockMvc.perform(authed(post("/api/v1/ai/stocks/005930/analysis"))
				.header("X-User-Id", "999")
				.contentType(MediaType.APPLICATION_JSON).content("{\"personalize\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.ticker").value("005930"))
			.andExpect(jsonPath("$.requestId").value("r1"));

		ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
		verify(relayService).relay(eq(AiRoute.STOCK_ANALYSIS), any(), isNull(), eq(42L), body.capture());
		org.assertj.core.api.Assertions.assertThat(body.getValue().get("personalize").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("나머지 9종의 경로·메서드가 라우트에 맞게 서비스로 간다")
	void otherRoutes() throws Exception {
		givenLoggedIn(42L);
		given(relayService.relay(any(), any(), any(), eq(42L), any()))
			.willReturn(ResponseEntity.ok(mapper.readTree("{\"content\":{}}")));
		given(wikiThesisService.update(eq(42L), eq("005930"), any()))
			.willReturn(ResponseEntity.ok(mapper.readTree("{\"content\":{}}")));

		mockMvc.perform(authed(post("/api/v1/ai/chat")).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
			.andExpect(status().isOk());
		mockMvc.perform(authed(post("/api/v1/ai/portfolio/diagnosis"))).andExpect(status().isOk());
		mockMvc.perform(authed(post("/api/v1/ai/portfolio/attribution")).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk());
		mockMvc.perform(authed(post("/api/v1/ai/orders/preview")).contentType(MediaType.APPLICATION_JSON).content("{\"orders\":[]}"))
			.andExpect(status().isOk());
		mockMvc.perform(authed(get("/api/v1/ai/briefing")).param("date", "2026-09-07")).andExpect(status().isOk());
		mockMvc.perform(authed(post("/api/v1/ai/feedback")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"requestId\":\"r\",\"rating\":\"up\"}")).andExpect(status().isOk());
		mockMvc.perform(authed(get("/api/v1/ai/wiki"))).andExpect(status().isOk());
		mockMvc.perform(authed(put("/api/v1/ai/wiki/theses/005930")).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk());
		mockMvc.perform(authed(delete("/api/v1/ai/wiki/facts/f1"))).andExpect(status().isOk());

		verify(relayService).relay(eq(AiRoute.CHAT), isNull(), isNull(), eq(42L), any());
		verify(relayService).relay(eq(AiRoute.PORTFOLIO_DIAGNOSIS), isNull(), isNull(), eq(42L), isNull());
		verify(relayService).relay(eq(AiRoute.PORTFOLIO_ATTRIBUTION), isNull(), isNull(), eq(42L), any());
		verify(relayService).relay(eq(AiRoute.ORDER_PREVIEW), isNull(), isNull(), eq(42L), any());
		ArgumentCaptor<MultiValueMap<String, String>> query = ArgumentCaptor.forClass(MultiValueMap.class);
		verify(relayService).relay(eq(AiRoute.BRIEFING), isNull(), query.capture(), eq(42L), isNull());
		org.assertj.core.api.Assertions.assertThat(query.getValue().getFirst("date")).isEqualTo("2026-09-07");
		verify(relayService).relay(eq(AiRoute.FEEDBACK), isNull(), isNull(), eq(42L), any());
		verify(relayService).relay(eq(AiRoute.WIKI), isNull(), any(), eq(42L), isNull());
		verify(wikiThesisService).update(eq(42L), eq("005930"), any());
		verify(relayService).relay(eq(AiRoute.WIKI_FACT_DELETE), eq(Map.of("factId", "f1")), isNull(), eq(42L), isNull());
	}

	/** apiSpec 10.1 (v0.8.8, 이슈 #56) — 매수 이유를 처음 적는 경로. 경로 변수가 없고 종목은 본문의 ticker 다. */
	@Test
	@DisplayName("POST /ai/wiki/theses — 경로 변수 없이 본문을 그대로 논지 서비스로 넘긴다")
	void createThesis() throws Exception {
		givenLoggedIn(42L);
		given(wikiThesisService.create(eq(42L), any()))
			.willReturn(ResponseEntity.ok(mapper.readTree(
				"{\"content\":{\"ticker\":\"000660\",\"status\":\"active\"},\"requestId\":\"r1\"}")));

		mockMvc.perform(authed(post("/api/v1/ai/wiki/theses")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"ticker\":\"000660\",\"text\":\"HBM 구조적 성장에 베팅\",\"linkedTradeId\":\"101\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.ticker").value("000660"))
			.andExpect(jsonPath("$.content.status").value("active"));

		ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
		verify(wikiThesisService).create(eq(42L), body.capture());
		org.assertj.core.api.Assertions.assertThat(body.getValue().get("ticker").asString()).isEqualTo("000660");
		org.assertj.core.api.Assertions.assertThat(body.getValue().get("linkedTradeId").asString()).isEqualTo("101");
	}

	@Test
	@DisplayName("AI 코드는 상태·code·requestId 그대로, 429 는 detail.reason 과 Retry-After 헤더, 도달 실패분은 백엔드 코드")
	void errorsReachClientAsContracted() throws Exception {
		givenLoggedIn(42L);
		given(relayService.relay(eq(AiRoute.CHAT), any(), any(), eq(42L), any()))
			.willThrow(new AiRelayException(HttpStatus.CONFLICT, "INSUFFICIENT_DATA", "데이터 부족", null, "req_1"));
		given(relayService.relay(eq(AiRoute.BRIEFING), any(), any(), eq(42L), any()))
			.willThrow(new AiRelayException(HttpStatus.TOO_MANY_REQUESTS, "AI_UPSTREAM_RATE_LIMITED",
				"요청이 너무 많습니다.", Map.of("reason", "request_rate_limit"), "req_2", 7L));
		given(relayService.relay(eq(AiRoute.WIKI), any(), any(), eq(42L), any()))
			.willThrow(new CustomException(AiErrorCode.AI_UPSTREAM_TIMEOUT));

		mockMvc.perform(authed(post("/api/v1/ai/chat")).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_DATA"))
			.andExpect(jsonPath("$.requestId").value("req_1"));
		mockMvc.perform(authed(get("/api/v1/ai/briefing")))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string(HttpHeaders.RETRY_AFTER, "7"))
			.andExpect(jsonPath("$.code").value("AI_UPSTREAM_RATE_LIMITED"))
			.andExpect(jsonPath("$.message").value("요청이 너무 많습니다."))
			.andExpect(jsonPath("$.detail.reason").value("request_rate_limit"))
			.andExpect(jsonPath("$.requestId").value("req_2"));
		mockMvc.perform(authed(get("/api/v1/ai/wiki")))
			.andExpect(status().isGatewayTimeout())
			.andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
			.andExpect(jsonPath("$.code").value("AI_UPSTREAM_TIMEOUT"))
			.andExpect(jsonPath("$.requestId").doesNotExist());
	}

	@Test
	@DisplayName("토큰이 없으면 401 이고 AI 서버로 가지 않는다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		verifyNoInteractions(relayService);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}
}
