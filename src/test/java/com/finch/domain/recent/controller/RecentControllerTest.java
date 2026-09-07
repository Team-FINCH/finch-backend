package com.finch.domain.recent.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.recent.dto.response.RecentSearchRes;
import com.finch.domain.recent.dto.response.RecentViewedRes;
import com.finch.domain.recent.service.RecentSearchService;
import com.finch.domain.recent.service.RecentViewedService;
import com.finch.global.config.SecurityConfig;
import com.finch.global.security.JwtProvider;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 최근 본 종목·최근 검색어 API 의 응답 계약(apiSpec §6.1·§6.2)과 DELETE 의 멱등 규칙을 고정한다. {@code @WebMvcTest} 라 Docker 없이 돈다.
 * FIFO·UPSERT 같은 DB 동작은 {@code RecentViewedServiceTest}·{@code RecentSearchServiceTest} 가 본다.
 */
@WebMvcTest(RecentController.class)
@Import(SecurityConfig.class)
class RecentControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RecentViewedService recentViewedService;

	@MockitoBean
	private RecentSearchService recentSearchService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Nested
	@DisplayName("최근 본 종목")
	class Viewed {

		@Test
		@DisplayName("items 는 stockCode · stockName · currentPrice · changeRate · viewedAt 이고 시세 없음은 null 이다")
		void returnsContractedBody() throws Exception {
			givenLoggedIn(42L);
			given(recentViewedService.list(42L)).willReturn(new RecentViewedRes(List.of(
				new RecentViewedRes.Item("005930", "삼성전자", 73_500L, new BigDecimal("-1.21"),
					OffsetDateTime.parse("2026-09-02T14:03:00+09:00")),
				new RecentViewedRes.Item("000660", "SK하이닉스", null, null,
					OffsetDateTime.parse("2026-09-02T14:02:00+09:00")))));

			mockMvc.perform(authed(get("/api/v1/stocks/recent")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].stockCode").value("005930"))
				.andExpect(jsonPath("$.items[0].stockName").value("삼성전자"))
				.andExpect(jsonPath("$.items[0].currentPrice").value(73500))
				.andExpect(jsonPath("$.items[0].changeRate").value(-1.21))
				.andExpect(jsonPath("$.items[0].viewedAt").value("2026-09-02T14:03:00+09:00"))
				// 시세가 없어도 키는 남는다.
				.andExpect(jsonPath("$.items[1].currentPrice").value(nullValue()))
				.andExpect(jsonPath("$.items[1].changeRate").value(nullValue()))
				// 등락 금액은 이 화면의 계약에 없다 (featureSpec 5장).
				.andExpect(jsonPath("$.items[0].changeAmount").doesNotExist());
		}

		@Test
		@DisplayName("본 적이 없으면 빈 items 다")
		void emptyList() throws Exception {
			givenLoggedIn(42L);
			given(recentViewedService.list(42L)).willReturn(new RecentViewedRes(List.of()));

			mockMvc.perform(authed(get("/api/v1/stocks/recent")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
		}

		@Test
		@DisplayName("전체 삭제와 개별 삭제는 본문 없이 204 다 — 없는 대상이어도 같다")
		void deletesReturnNoContent() throws Exception {
			givenLoggedIn(42L);

			mockMvc.perform(authed(delete("/api/v1/stocks/recent")))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
			mockMvc.perform(authed(delete("/api/v1/stocks/recent/999999")))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

			verify(recentViewedService).deleteAll(42L);
			verify(recentViewedService).delete(42L, "999999");
		}

		/** {@code /stocks/recent} 가 {@code /stocks/{stockCode}} 보다 먼저 잡혀야 한다 — 리터럴이 변수보다 앞이다. */
		@Test
		@DisplayName("경로가 종목 상세와 겹치지 않는다")
		void pathDoesNotClashWithStockDetail() throws Exception {
			givenLoggedIn(42L);
			given(recentViewedService.list(42L)).willReturn(new RecentViewedRes(List.of()));

			mockMvc.perform(authed(get("/api/v1/stocks/recent"))).andExpect(status().isOk());

			verify(recentViewedService).list(42L);
		}
	}

	@Nested
	@DisplayName("최근 검색어")
	class Searched {

		@Test
		@DisplayName("items 는 keywordId · keyword · searchedAt 셋이고 시세 필드가 없다")
		void returnsContractedBody() throws Exception {
			givenLoggedIn(42L);
			given(recentSearchService.list(42L)).willReturn(new RecentSearchRes(List.of(
				new RecentSearchRes.Item(42L, "삼성", OffsetDateTime.parse("2026-09-02T14:03:00+09:00")))));

			mockMvc.perform(authed(get("/api/v1/stocks/search/recent")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].keywordId").value(42))
				.andExpect(jsonPath("$.items[0].keyword").value("삼성"))
				.andExpect(jsonPath("$.items[0].searchedAt").value("2026-09-02T14:03:00+09:00"))
				// 검색어는 종목이 아니라 문자열이다 (apiSpec 6.2).
				.andExpect(jsonPath("$.items[0].currentPrice").doesNotExist())
				.andExpect(jsonPath("$.items[0].stockCode").doesNotExist());
		}

		@Test
		@DisplayName("전체 삭제와 개별 삭제는 204 다 — 남의 keywordId 를 지목해도 같다")
		void deletesReturnNoContent() throws Exception {
			givenLoggedIn(42L);

			mockMvc.perform(authed(delete("/api/v1/stocks/search/recent")))
				.andExpect(status().isNoContent());
			mockMvc.perform(authed(delete("/api/v1/stocks/search/recent/999")))
				.andExpect(status().isNoContent());

			verify(recentSearchService).deleteAll(42L);
			verify(recentSearchService).delete(42L, 999L);
		}

		@Test
		@DisplayName("keywordId 가 숫자가 아니면 400 INVALID_REQUEST 다")
		void rejectsNonNumericKeywordId() throws Exception {
			givenLoggedIn(42L);

			mockMvc.perform(authed(delete("/api/v1/stocks/search/recent/abc")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			verifyNoInteractions(recentSearchService);
		}
	}

	@Test
	@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/stocks/recent"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
		mockMvc.perform(delete("/api/v1/stocks/search/recent"))
			.andExpect(status().isUnauthorized());

		verifyNoInteractions(recentViewedService, recentSearchService);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}
}
