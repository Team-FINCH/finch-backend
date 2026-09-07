package com.finch.domain.stock.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.stock.dto.request.CandlePeriod;
import com.finch.domain.stock.dto.response.CandleRes;
import com.finch.domain.stock.dto.response.StockDetailRes;
import com.finch.domain.stock.dto.response.StockPriceRes;
import com.finch.domain.stock.dto.response.StockSearchRes;
import com.finch.domain.stock.entity.Market;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.stock.service.StockService;
import com.finch.global.config.SecurityConfig;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * 종목 API 세 개의 응답 계약(apiSpec §5.1~§5.3)과 파라미터 검증이 {@code INVALID_REQUEST} 로 답하는지, 고유 코드 {@code STOCK_NOT_FOUND}
 * 가 404 로 나가는지 고정한다. {@code @WebMvcTest} 라 Docker 없이 돈다. DB 동작은 {@code StockServiceTest}.
 */
@WebMvcTest(StockController.class)
@Import(SecurityConfig.class)
class StockControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private StockService stockService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Nested
	@DisplayName("GET /stocks/search")
	class Search {

		@Test
		@DisplayName("items 는 stockCode · stockName · market · 시세 셋 · suspended 이고 시세 없음은 null 이다")
		void returnsContractedBody() throws Exception {
			givenLoggedIn(42L);
			given(stockService.search(42L, "삼성", 10)).willReturn(new StockSearchRes(List.of(
				new StockSearchRes.Item("005930", "삼성전자", Market.KOSPI, 73_500L, -900L, new BigDecimal("-1.21"), false),
				new StockSearchRes.Item("009150", "삼성전기", Market.KOSPI, null, null, null, true))));

			mockMvc.perform(authed(get("/api/v1/stocks/search").param("keyword", "삼성")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].stockCode").value("005930"))
				.andExpect(jsonPath("$.items[0].stockName").value("삼성전자"))
				.andExpect(jsonPath("$.items[0].market").value("KOSPI"))
				.andExpect(jsonPath("$.items[0].currentPrice").value(73500))
				.andExpect(jsonPath("$.items[0].changeAmount").value(-900))
				.andExpect(jsonPath("$.items[0].changeRate").value(-1.21))
				.andExpect(jsonPath("$.items[0].suspended").value(false))
				.andExpect(jsonPath("$.items[1].currentPrice").value(nullValue()))
				.andExpect(jsonPath("$.items[1].changeRate").value(nullValue()))
				.andExpect(jsonPath("$.items[1].suspended").value(true));
		}

		@Test
		@DisplayName("size 를 주면 그대로, 생략하면 10 이다")
		void sizeDefault() throws Exception {
			givenLoggedIn(42L);
			given(stockService.search(anyLong(), anyString(), any(Integer.class).intValue()))
				.willReturn(new StockSearchRes(List.of()));

			mockMvc.perform(authed(get("/api/v1/stocks/search").param("keyword", "삼성").param("size", "3")))
				.andExpect(status().isOk());
			mockMvc.perform(authed(get("/api/v1/stocks/search").param("keyword", "삼성")))
				.andExpect(status().isOk());

			verify(stockService).search(42L, "삼성", 3);
			verify(stockService).search(42L, "삼성", 10);
		}

		@Test
		@DisplayName("keyword 가 2글자 미만이거나 없으면 400 INVALID_REQUEST 이고 detail 에 keyword 가 있다")
		void rejectsShortOrMissingKeyword() throws Exception {
			givenLoggedIn(42L);

			mockMvc.perform(authed(get("/api/v1/stocks/search").param("keyword", "삼")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail.keyword").exists());
			mockMvc.perform(authed(get("/api/v1/stocks/search")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail.keyword").exists());

			verifyNoInteractions(stockService);
		}

		@Test
		@DisplayName("size 가 1~10 밖이면 400 INVALID_REQUEST 이고 detail 에 size 가 있다")
		void rejectsSizeOutOfRange() throws Exception {
			givenLoggedIn(42L);

			for (String size : new String[] {"0", "11", "abc"}) {
				mockMvc.perform(authed(get("/api/v1/stocks/search").param("keyword", "삼성").param("size", size)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
					.andExpect(jsonPath("$.detail.size").exists());
			}
			verifyNoInteractions(stockService);
		}

		@Test
		@DisplayName("토큰이 없으면 401 — 검색도 사용자를 알아야 최근 검색어를 기록한다")
		void requiresAuthentication() throws Exception {
			mockMvc.perform(get("/api/v1/stocks/search").param("keyword", "삼성"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
			verifyNoInteractions(stockService);
		}
	}

	@Nested
	@DisplayName("GET /stocks/{stockCode}")
	class Detail {

		@Test
		@DisplayName("명세 예시 모양이다 — holding 은 있으면 4필드, asOf 는 KST")
		void returnsContractedBody() throws Exception {
			givenLoggedIn(42L);
			given(stockService.detail(42L, "005930")).willReturn(new StockDetailRes("005930", "삼성전자", Market.KOSPI,
				73_500L, 74_400L, -900L, new BigDecimal("-1.21"), false, null, true,
				OffsetDateTime.parse("2026-08-20T14:30:00+09:00"),
				new StockDetailRes.Holding(10, 71_200L, 23_000L, new BigDecimal("3.23"))));

			mockMvc.perform(authed(get("/api/v1/stocks/005930")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stockCode").value("005930"))
				.andExpect(jsonPath("$.previousClose").value(74400))
				.andExpect(jsonPath("$.suspendedReason").value(nullValue()))
				.andExpect(jsonPath("$.watched").value(true))
				.andExpect(jsonPath("$.asOf").value("2026-08-20T14:30:00+09:00"))
				.andExpect(jsonPath("$.holding.quantity").value(10))
				.andExpect(jsonPath("$.holding.avgBuyPrice").value(71200))
				.andExpect(jsonPath("$.holding.evaluationProfit").value(23000))
				.andExpect(jsonPath("$.holding.evaluationProfitRate").value(3.23));
		}

		/** contracts C76 — 프론트는 {@code holding !== null} 로 보유 여부를 판단한다. 키는 있고 값이 null 이어야 한다. */
		@Test
		@DisplayName("보유 없음은 holding: null 이고 시세 없음은 currentPrice · asOf null 이다 — 키는 유지된다")
		void nullsKeepKeys() throws Exception {
			givenLoggedIn(42L);
			given(stockService.detail(42L, "005930")).willReturn(new StockDetailRes("005930", "삼성전자", Market.KOSPI,
				null, 74_400L, null, null, true, "거래정지", false, null, null));

			mockMvc.perform(authed(get("/api/v1/stocks/005930")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.holding").value(nullValue()))
				.andExpect(jsonPath("$.currentPrice").value(nullValue()))
				.andExpect(jsonPath("$.asOf").value(nullValue()))
				.andExpect(jsonPath("$.suspended").value(true))
				.andExpect(jsonPath("$.suspendedReason").value("거래정지"));
		}

		@Test
		@DisplayName("없는 종목은 404 STOCK_NOT_FOUND")
		void notFound() throws Exception {
			givenLoggedIn(42L);
			given(stockService.detail(42L, "999999")).willThrow(new CustomException(StockErrorCode.STOCK_NOT_FOUND));

			mockMvc.perform(authed(get("/api/v1/stocks/999999")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
		}
	}

	@Nested
	@DisplayName("GET /stocks/{stockCode}/price")
	class Price {

		/** 단건은 stock 이 소유한다 — 없는 종목에 404 를 내야 하고 그 판정은 stock 만 할 수 있다 (다건은 price 도메인). */
		@Test
		@DisplayName("응답은 stockCode · 시세 셋 · asOf(KST) · stale 이다")
		void returnsContractedBody() throws Exception {
			givenLoggedIn(42L);
			given(stockService.price("005930")).willReturn(new StockPriceRes("005930", 73_500L, -900L,
				new BigDecimal("-1.21"), OffsetDateTime.parse("2026-08-20T14:30:00+09:00"), false));

			mockMvc.perform(authed(get("/api/v1/stocks/005930/price")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stockCode").value("005930"))
				.andExpect(jsonPath("$.currentPrice").value(73500))
				.andExpect(jsonPath("$.changeAmount").value(-900))
				.andExpect(jsonPath("$.changeRate").value(-1.21))
				.andExpect(jsonPath("$.asOf").value("2026-08-20T14:30:00+09:00"))
				.andExpect(jsonPath("$.stale").value(false));
		}

		@Test
		@DisplayName("값 없음은 시세 넷이 null 이고 stale 은 true 다 — 키는 유지된다")
		void missingPriceKeepsKeys() throws Exception {
			givenLoggedIn(42L);
			given(stockService.price("005930"))
				.willReturn(new StockPriceRes("005930", null, null, null, null, true));

			mockMvc.perform(authed(get("/api/v1/stocks/005930/price")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currentPrice").value(nullValue()))
				.andExpect(jsonPath("$.changeAmount").value(nullValue()))
				.andExpect(jsonPath("$.changeRate").value(nullValue()))
				.andExpect(jsonPath("$.asOf").value(nullValue()))
				.andExpect(jsonPath("$.stale").value(true));
		}

		@Test
		@DisplayName("없는 종목은 404 STOCK_NOT_FOUND — 시세가 없는 것과 다른 상태다")
		void notFound() throws Exception {
			givenLoggedIn(42L);
			given(stockService.price("999999")).willThrow(new CustomException(StockErrorCode.STOCK_NOT_FOUND));

			mockMvc.perform(authed(get("/api/v1/stocks/999999/price")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
		}
	}

	@Nested
	@DisplayName("GET /stocks/{stockCode}/candles")
	class Candles {

		@Test
		@DisplayName("period 기본 1M · interval DAY · date 는 yyyy-MM-dd")
		void returnsContractedBody() throws Exception {
			givenLoggedIn(42L);
			given(stockService.candles("005930", CandlePeriod.ONE_MONTH)).willReturn(new CandleRes("005930", "1M", "DAY",
				List.of(new CandleRes.Candle(LocalDate.parse("2026-08-20"), 74_000, 74_500, 73_100, 73_500, 12_345_678L))));

			mockMvc.perform(authed(get("/api/v1/stocks/005930/candles")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stockCode").value("005930"))
				.andExpect(jsonPath("$.period").value("1M"))
				.andExpect(jsonPath("$.interval").value("DAY"))
				.andExpect(jsonPath("$.candles[0].date").value("2026-08-20"))
				.andExpect(jsonPath("$.candles[0].open").value(74000))
				.andExpect(jsonPath("$.candles[0].volume").value(12345678));
		}

		@Test
		@DisplayName("period 는 1M · 3M · 1Y 만 — 밖이면 400 INVALID_REQUEST 이고 detail 에 period 가 있다")
		void rejectsUnknownPeriod() throws Exception {
			givenLoggedIn(42L);
			given(stockService.candles(anyString(), any())).willReturn(new CandleRes("005930", "1Y", "DAY", List.of()));

			mockMvc.perform(authed(get("/api/v1/stocks/005930/candles").param("period", "1Y"))).andExpect(status().isOk());
			verify(stockService).candles("005930", CandlePeriod.ONE_YEAR);

			mockMvc.perform(authed(get("/api/v1/stocks/005930/candles").param("period", "2W")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail.period").exists());
		}

		@Test
		@DisplayName("없는 종목은 404 STOCK_NOT_FOUND")
		void notFound() throws Exception {
			givenLoggedIn(42L);
			given(stockService.candles("999999", CandlePeriod.ONE_MONTH))
				.willThrow(new CustomException(StockErrorCode.STOCK_NOT_FOUND));

			mockMvc.perform(authed(get("/api/v1/stocks/999999/candles")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
		}
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}
}
