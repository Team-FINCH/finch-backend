package com.finch.domain.price.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.global.config.SecurityConfig;
import com.finch.global.security.JwtProvider;
import com.finch.global.util.StockUniverse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 다건 현재가 조회의 응답 계약(apiSpec 5.5)과 파라미터 검증을 고정한다. {@code @WebMvcTest} 라 Docker 없이 돈다.
 * {@code stale} 판정과 캐시 동작은 {@code PriceQueryServiceTest} 가 본다.
 */
@WebMvcTest(PriceController.class)
@Import(SecurityConfig.class)
class PriceControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PriceQueryPort priceQueryPort;

	@MockitoBean
	private JwtProvider jwtProvider;

	/** 종목 범위는 이 테스트의 관심사가 아니다 — 전부 통과시킨다. 범위 판정은 {@code StockUniverseGateTest}. */
	@MockitoBean
	private StockUniverse universe;

	@BeforeEach
	void universePassesEverything() {
		given(universe.filter(any())).willAnswer(invocation -> List.copyOf(invocation.<Collection<String>>getArgument(0)));
	}

	@Test
	@DisplayName("items 는 stockCode · 시세 셋 · asOf · stale 이고 값 없음은 넷 다 null 이다")
	void returnsContractedBody() throws Exception {
		givenLoggedIn(42L);
		Map<String, PriceSnapshot> prices = new LinkedHashMap<>();
		prices.put("005930", new PriceSnapshot(73_500L, -900L, new BigDecimal("-1.21"),
			Instant.parse("2026-08-20T05:30:00Z"), false));
		prices.put("000660", PriceSnapshot.missing());
		given(priceQueryPort.latestAll(any())).willReturn(prices);

		mockMvc.perform(authed(get("/api/v1/stocks/prices").param("stockCodes", "005930,000660")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].stockCode").value("005930"))
			.andExpect(jsonPath("$.items[0].currentPrice").value(73500))
			.andExpect(jsonPath("$.items[0].changeAmount").value(-900))
			.andExpect(jsonPath("$.items[0].changeRate").value(-1.21))
			.andExpect(jsonPath("$.items[0].asOf").value("2026-08-20T14:30:00+09:00"))
			.andExpect(jsonPath("$.items[0].stale").value(false))
			// 값 없음도 항목으로 담긴다 — 빠뜨리면 프론트가 어느 종목이 왜 없는지 따져야 한다.
			.andExpect(jsonPath("$.items[1].stockCode").value("000660"))
			.andExpect(jsonPath("$.items[1].currentPrice").value(nullValue()))
			.andExpect(jsonPath("$.items[1].asOf").value(nullValue()))
			.andExpect(jsonPath("$.items[1].stale").value(true));
	}

	@Test
	@DisplayName("중복 코드는 한 번만 담고 공백은 걷어낸다")
	void normalizesCodes() throws Exception {
		givenLoggedIn(42L);
		given(priceQueryPort.latestAll(any())).willReturn(Map.of());

		mockMvc.perform(authed(get("/api/v1/stocks/prices").param("stockCodes", "005930, 005930 ,000660")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2));

		verify(priceQueryPort).latestAll(List.of("005930", "000660"));
	}

	@Test
	@DisplayName("stockCodes 가 없거나 비어 있으면 400 INVALID_REQUEST 이고 detail 에 stockCodes 가 있다")
	void rejectsMissingOrBlank() throws Exception {
		givenLoggedIn(42L);

		mockMvc.perform(authed(get("/api/v1/stocks/prices")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.stockCodes").exists());
		// ?stockCodes= 는 빈 문자열 하나짜리 목록으로 바인딩된다 — 공백을 걷어낸 뒤에 세야 잡힌다.
		mockMvc.perform(authed(get("/api/v1/stocks/prices").param("stockCodes", "")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.stockCodes").exists());

		verifyNoInteractions(priceQueryPort);
	}

	@Test
	@DisplayName("50건을 넘으면 400 INVALID_REQUEST — 50건 정확히는 통과한다")
	void rejectsOverFifty() throws Exception {
		givenLoggedIn(42L);
		given(priceQueryPort.latestAll(any())).willReturn(Map.of());

		mockMvc.perform(authed(get("/api/v1/stocks/prices").param("stockCodes", codes(50))))
			.andExpect(status().isOk());
		mockMvc.perform(authed(get("/api/v1/stocks/prices").param("stockCodes", codes(51))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.stockCodes").exists());
	}

	/** 없는 종목코드도 값 없음으로 답한다 — 존재 판정은 이 엔드포인트의 일이 아니다 (단건은 stock 이 소유한다). */
	@Test
	@DisplayName("존재하지 않는 코드도 404 가 아니라 값 없음 항목이다")
	void unknownCodeIsNotAnError() throws Exception {
		givenLoggedIn(42L);
		given(priceQueryPort.latestAll(any())).willReturn(Map.of());

		mockMvc.perform(authed(get("/api/v1/stocks/prices").param("stockCodes", "ZZZZZZ")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].stockCode").value("ZZZZZZ"))
			.andExpect(jsonPath("$.items[0].stale").value(true));
	}

	@Test
	@DisplayName("토큰이 없으면 401 이고 포트는 돌지 않는다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/stocks/prices").param("stockCodes", "005930"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		verifyNoInteractions(priceQueryPort);
	}

	private static String codes(int count) {
		return IntStream.range(0, count).mapToObj(i -> String.format("%06d", i)).collect(Collectors.joining(","));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}
}
