package com.finch.domain.price.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.global.config.SecurityConfig;
import com.finch.global.security.JwtProvider;
import com.finch.global.util.MarketClock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 시장 상태 조회의 응답 계약(apiSpec 5.8)을 고정한다. 판정 자체는 {@code MarketClockTest} 가 본다. */
@WebMvcTest(MarketStatusController.class)
@Import(SecurityConfig.class)
class MarketStatusControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MarketClock marketClock;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("애프터마켓이면 open=false · quotesLive=true · session=AFTER · nextChangeAt 은 KST 표기다")
	void afterMarket() throws Exception {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(42L);
		given(marketClock.isOpen()).willReturn(false);
		given(marketClock.sessionNow()).willReturn(MarketClock.Session.AFTER);
		given(marketClock.nextChangeAt()).willReturn(Instant.parse("2026-09-14T11:00:00Z"));

		mockMvc.perform(get("/api/v1/market/status").header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.open").value(false))
			.andExpect(jsonPath("$.quotesLive").value(true))
			.andExpect(jsonPath("$.session").value("AFTER"))
			.andExpect(jsonPath("$.nextChangeAt").value("2026-09-14T20:00:00+09:00"));
	}

	@Test
	@DisplayName("always-open 이면 open·quotesLive 가 true 이고 nextChangeAt 은 null 이다")
	void alwaysOpen() throws Exception {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(42L);
		given(marketClock.isOpen()).willReturn(true);
		given(marketClock.sessionNow()).willReturn(MarketClock.Session.REGULAR);
		given(marketClock.nextChangeAt()).willReturn(null);

		mockMvc.perform(get("/api/v1/market/status").header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.open").value(true))
			.andExpect(jsonPath("$.quotesLive").value(true))
			.andExpect(jsonPath("$.session").value("REGULAR"))
			.andExpect(jsonPath("$.nextChangeAt").value(nullValue()));
	}

	@Test
	@DisplayName("토큰이 없으면 401 이다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/market/status")).andExpect(status().isUnauthorized());
	}
}
