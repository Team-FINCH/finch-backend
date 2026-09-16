package com.finch.domain.watchlist.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.watchlist.dto.request.WatchlistSort;
import com.finch.domain.watchlist.dto.response.WatchlistRes;
import com.finch.domain.watchlist.exception.WatchlistErrorCode;
import com.finch.domain.watchlist.service.WatchlistService;
import com.finch.global.config.SecurityConfig;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 관심 종목 API 의 응답 계약(apiSpec §6.3)과 11.2 가 정한 고유 에러 코드 3종을 고정한다. {@code @WebMvcTest} 라 Docker 없이 돈다.
 * 판정 <b>순서</b>와 DB 동작은 {@code WatchlistServiceTest} 가 본다.
 */
@WebMvcTest(WatchlistController.class)
@Import(SecurityConfig.class)
class WatchlistControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private WatchlistService watchlistService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("응답은 count · maxCount · items 이고 items 는 market · suspended · 시세 셋 · held · registeredAt 을 담는다")
	void returnsContractedBody() throws Exception {
		givenLoggedIn(42L);
		given(watchlistService.list(42L, WatchlistSort.REGISTERED)).willReturn(new WatchlistRes(2, 50, List.of(
			new WatchlistRes.Item("005930", "삼성전자", "KOSPI", false, 73_500L, -900L, new BigDecimal("-1.21"), true,
				OffsetDateTime.parse("2026-09-02T14:03:00+09:00")),
			new WatchlistRes.Item("000660", "SK하이닉스", "KOSDAQ", true, null, null, null, false,
				OffsetDateTime.parse("2026-09-02T14:02:00+09:00")))));

		mockMvc.perform(authed(get("/api/v1/watchlist")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(2))
			.andExpect(jsonPath("$.maxCount").value(50))
			.andExpect(jsonPath("$.items[0].stockCode").value("005930"))
			.andExpect(jsonPath("$.items[0].stockName").value("삼성전자"))
			// v0.8.20 (이슈 #67). 종목 상세(§5.2)와 같은 값이라 프론트가 KOSPI → "코스피" 매핑을 그대로 쓴다.
			.andExpect(jsonPath("$.items[0].market").value("KOSPI"))
			.andExpect(jsonPath("$.items[0].suspended").value(false))
			.andExpect(jsonPath("$.items[1].market").value("KOSDAQ"))
			.andExpect(jsonPath("$.items[1].suspended").value(true))
			.andExpect(jsonPath("$.items[0].currentPrice").value(73500))
			.andExpect(jsonPath("$.items[0].changeAmount").value(-900))
			.andExpect(jsonPath("$.items[0].changeRate").value(-1.21))
			.andExpect(jsonPath("$.items[0].held").value(true))
			.andExpect(jsonPath("$.items[0].registeredAt").value("2026-09-02T14:03:00+09:00"))
			.andExpect(jsonPath("$.items[1].held").value(false))
			.andExpect(jsonPath("$.items[1].currentPrice").value(nullValue()));
	}

	@Test
	@DisplayName("sort 를 생략하면 REGISTERED, 주면 그대로 넘긴다")
	void sortDefault() throws Exception {
		givenLoggedIn(42L);
		given(watchlistService.list(anyLong(), any())).willReturn(new WatchlistRes(0, 50, List.of()));

		mockMvc.perform(authed(get("/api/v1/watchlist"))).andExpect(status().isOk());
		mockMvc.perform(authed(get("/api/v1/watchlist").param("sort", "CHANGE_RATE"))).andExpect(status().isOk());

		verify(watchlistService).list(42L, WatchlistSort.REGISTERED);
		verify(watchlistService).list(42L, WatchlistSort.CHANGE_RATE);
	}

	@Test
	@DisplayName("sort 가 열거값 밖이면 400 INVALID_REQUEST 이고 detail 에 sort 가 있다")
	void rejectsUnknownSort() throws Exception {
		givenLoggedIn(42L);

		mockMvc.perform(authed(get("/api/v1/watchlist").param("sort", "FOO")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.sort").exists());

		verifyNoInteractions(watchlistService);
	}

	@Test
	@DisplayName("등록은 본문 없이 201 이다")
	void addReturnsCreated() throws Exception {
		givenLoggedIn(42L);

		mockMvc.perform(authed(post("/api/v1/watchlist")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"stockCode\":\"005930\"}"))
			.andExpect(status().isCreated())
			.andExpect(content().string(""));

		verify(watchlistService).add(42L, "005930");
	}

	@Test
	@DisplayName("stockCode 가 비어 있으면 400 INVALID_REQUEST 이고 서비스는 돌지 않는다")
	void rejectsBlankStockCode() throws Exception {
		givenLoggedIn(42L);

		mockMvc.perform(authed(post("/api/v1/watchlist")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"stockCode\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.stockCode").exists());

		verifyNoInteractions(watchlistService);
	}

	@Test
	@DisplayName("없는 종목은 404, 중복은 409 ALREADY_EXISTS, 한도 초과는 409 LIMIT_EXCEEDED")
	void addErrorCodes() throws Exception {
		givenLoggedIn(42L);

		willThrow(new CustomException(StockErrorCode.STOCK_NOT_FOUND)).given(watchlistService).add(anyLong(), anyString());
		mockMvc.perform(add()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));

		willThrow(new CustomException(WatchlistErrorCode.WATCHLIST_ALREADY_EXISTS))
			.given(watchlistService).add(anyLong(), anyString());
		mockMvc.perform(add()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WATCHLIST_ALREADY_EXISTS"));

		willThrow(new CustomException(WatchlistErrorCode.WATCHLIST_LIMIT_EXCEEDED))
			.given(watchlistService).add(anyLong(), anyString());
		mockMvc.perform(add()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WATCHLIST_LIMIT_EXCEEDED"))
			.andExpect(jsonPath("$.message").value("관심 종목은 최대 50개까지 등록할 수 있어요"));
	}

	@Test
	@DisplayName("해제는 본문 없이 204 다 — 담긴 적 없어도 같다")
	void removeReturnsNoContent() throws Exception {
		givenLoggedIn(42L);

		mockMvc.perform(authed(delete("/api/v1/watchlist/999999")))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(watchlistService).remove(42L, "999999");
	}

	@Test
	@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/watchlist"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		verifyNoInteractions(watchlistService);
	}

	private MockHttpServletRequestBuilder add() {
		return authed(post("/api/v1/watchlist")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"stockCode\":\"005930\"}");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}
}
