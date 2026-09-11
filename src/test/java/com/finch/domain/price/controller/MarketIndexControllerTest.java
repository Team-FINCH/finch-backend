package com.finch.domain.price.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.price.MarketIndex;
import com.finch.domain.price.service.IndexQueryService;
import com.finch.domain.price.service.IndexQueryService.IndexSnapshot;
import com.finch.global.config.SecurityConfig;
import com.finch.global.security.JwtProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 시장 지수 조회의 응답 계약(apiSpec 5.7)을 고정한다. {@code @WebMvcTest} 라 Docker 없이 돈다.
 * {@code stale} 판정과 캐시 동작은 {@code IndexQueryServiceTest} 가 본다.
 */
@WebMvcTest(MarketIndexController.class)
@Import(SecurityConfig.class)
class MarketIndexControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private IndexQueryService indexQueryService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("items 는 indexCode · 지수 셋 · asOf · stale 이고, 지수는 소수 그대로, 값 없음은 넷 다 null 이다")
	void returnsContractedBody() throws Exception {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(42L);
		given(indexQueryService.latestAll()).willReturn(List.of(
			new IndexSnapshot(MarketIndex.KOSPI, new BigDecimal("2600.54"), new BigDecimal("-12.31"),
				new BigDecimal("-0.47"), Instant.parse("2026-09-11T05:30:05Z"), false),
			IndexSnapshot.missing(MarketIndex.KOSDAQ)));

		mockMvc.perform(get("/api/v1/market/indices").header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].indexCode").value("KOSPI"))
			.andExpect(jsonPath("$.items[0].currentValue").value(2600.54))
			.andExpect(jsonPath("$.items[0].changeValue").value(-12.31))
			.andExpect(jsonPath("$.items[0].changeRate").value(-0.47))
			.andExpect(jsonPath("$.items[0].asOf").value("2026-09-11T14:30:05+09:00"))
			.andExpect(jsonPath("$.items[0].stale").value(false))
			.andExpect(jsonPath("$.items[1].indexCode").value("KOSDAQ"))
			.andExpect(jsonPath("$.items[1].currentValue").value(nullValue()))
			.andExpect(jsonPath("$.items[1].changeValue").value(nullValue()))
			.andExpect(jsonPath("$.items[1].changeRate").value(nullValue()))
			.andExpect(jsonPath("$.items[1].asOf").value(nullValue()))
			.andExpect(jsonPath("$.items[1].stale").value(true));
	}

	@Test
	@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/market/indices"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		verifyNoInteractions(indexQueryService);
	}
}
