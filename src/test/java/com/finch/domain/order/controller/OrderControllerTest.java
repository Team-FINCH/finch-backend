package com.finch.domain.order.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.order.dto.request.OrderReq;
import com.finch.domain.order.dto.response.OrderAvailableRes;
import com.finch.domain.order.dto.response.OrderRes;
import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.exception.OrderErrorCode;
import com.finch.domain.order.service.OrderService;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.global.config.SecurityConfig;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import com.finch.global.util.KstTime;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * 주문 API 둘의 응답 계약(apiSpec 7.1·7.3)과 apiSpec 11.2 가 정한 고유 에러 코드 전부를 고정한다.
 * <p>
 * {@code @WebMvcTest} 라 Docker 없이 돌고 <b>멱등성 필터는 여기 없다</b>. {@code Idempotency-Key} 누락 400 과 재전송 재생은
 * {@code OrderIdempotencyTest} 가 실제 필터 체인으로 본다. 판정 <b>순서</b>는 {@code OrderValidatorTest}(순수)와
 * {@code OrderServiceTest}(DB)가 본다. 여기서는 서비스가 던진 코드가 그대로 나가는지와 요청 검증이 어느 코드로 답하는지를 본다.
 */
@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";
	private static final String BUY_BODY = "{\"stockCode\":\"005930\",\"side\":\"BUY\",\"quantity\":10}";
	private static final OrderReq BUY_REQ = new OrderReq("005930", OrderSide.BUY, 10L);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrderService orderService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Nested
	@DisplayName("POST /orders")
	class Place {

		@Test
		@DisplayName("201 과 apiSpec 7.1 의 본문 — 매수는 realizedProfit 이 null 이고 필드는 남는다, executedAt 은 KST")
		void returnsContractedBody() throws Exception {
			givenLoggedIn(42L);
			given(orderService.place(42L, BUY_REQ)).willReturn(new OrderRes(101L, "005930", "삼성전자", OrderSide.BUY,
				10, 73_500, 735_000, KstTime.toResponse(Instant.parse("2026-08-20T05:31:10Z")), 515_000, null));

			mockMvc.perform(place(BUY_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.orderId").value(101))
				.andExpect(jsonPath("$.stockCode").value("005930"))
				.andExpect(jsonPath("$.stockName").value("삼성전자"))
				.andExpect(jsonPath("$.side").value("BUY"))
				.andExpect(jsonPath("$.quantity").value(10))
				.andExpect(jsonPath("$.executedPrice").value(73500))
				.andExpect(jsonPath("$.executedAmount").value(735000))
				.andExpect(jsonPath("$.executedAt").value("2026-08-20T14:31:10+09:00"))
				.andExpect(jsonPath("$.cashBalanceAfter").value(515000))
				.andExpect(jsonPath("$.realizedProfit").value(nullValue()));
		}

		@Test
		@DisplayName("매도는 realizedProfit 에 값이 있다")
		void sellCarriesRealizedProfit() throws Exception {
			givenLoggedIn(42L);
			OrderReq sell = new OrderReq("005930", OrderSide.SELL, 4L);
			given(orderService.place(42L, sell)).willReturn(new OrderRes(102L, "005930", "삼성전자", OrderSide.SELL,
				4, 80_000, 320_000, KstTime.toResponse(Instant.parse("2026-08-20T05:31:10Z")), 620_000, 40_000L));

			mockMvc.perform(place("{\"stockCode\":\"005930\",\"side\":\"SELL\",\"quantity\":4}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.side").value("SELL"))
				.andExpect(jsonPath("$.realizedProfit").value(40000));
		}

		/** apiSpec 11.2 가 이 엔드포인트에 적은 고유 코드 7종. 상태·detail 이 계약 그대로 나가는지 본다. */
		@Test
		@DisplayName("ORDER_QUANTITY_INVALID 400 — 서비스 판정이고 INVALID_REQUEST 가 아니다")
		void quantityInvalid() throws Exception {
			givenLoggedIn(42L);
			OrderReq zero = new OrderReq("005930", OrderSide.BUY, 0L);
			given(orderService.place(42L, zero)).willThrow(new CustomException(OrderErrorCode.ORDER_QUANTITY_INVALID));

			mockMvc.perform(place("{\"stockCode\":\"005930\",\"side\":\"BUY\",\"quantity\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ORDER_QUANTITY_INVALID"));
		}

		@Test
		@DisplayName("STOCK_NOT_FOUND 404")
		void stockNotFound() throws Exception {
			givenLoggedIn(42L);
			given(orderService.place(42L, BUY_REQ)).willThrow(new CustomException(StockErrorCode.STOCK_NOT_FOUND));

			mockMvc.perform(place(BUY_BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
		}

		@Test
		@DisplayName("ORDER_MARKET_CLOSED 409")
		void marketClosed() throws Exception {
			givenLoggedIn(42L);
			given(orderService.place(42L, BUY_REQ)).willThrow(new CustomException(OrderErrorCode.ORDER_MARKET_CLOSED));

			mockMvc.perform(place(BUY_BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_MARKET_CLOSED"))
				.andExpect(jsonPath("$.message").value("지금은 주문할 수 없어요 (거래 시간 09:00~15:30, 16:00~20:00)"));
		}

		@Test
		@DisplayName("ORDER_STOCK_SUSPENDED 409 이고 detail.reason 을 싣는다")
		void stockSuspended() throws Exception {
			givenLoggedIn(42L);
			given(orderService.place(42L, BUY_REQ))
				.willThrow(new CustomException(OrderErrorCode.ORDER_STOCK_SUSPENDED, Map.of("reason", "관리종목")));

			mockMvc.perform(place(BUY_BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_STOCK_SUSPENDED"))
				.andExpect(jsonPath("$.detail.reason").value("관리종목"));
		}

		@Test
		@DisplayName("ORDER_PRICE_UNAVAILABLE 503")
		void priceUnavailable() throws Exception {
			givenLoggedIn(42L);
			given(orderService.place(42L, BUY_REQ)).willThrow(new CustomException(OrderErrorCode.ORDER_PRICE_UNAVAILABLE));

			mockMvc.perform(place(BUY_BODY))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("ORDER_PRICE_UNAVAILABLE"));
		}

		@Test
		@DisplayName("ORDER_INSUFFICIENT_CASH 409 이고 detail 은 {required, available} 이다 (apiSpec 1.3 예시)")
		void insufficientCash() throws Exception {
			givenLoggedIn(42L);
			given(orderService.place(42L, BUY_REQ)).willThrow(new CustomException(OrderErrorCode.ORDER_INSUFFICIENT_CASH,
				Map.of("required", 735_000L, "available", 512_000L)));

			mockMvc.perform(place(BUY_BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_INSUFFICIENT_CASH"))
				.andExpect(jsonPath("$.message").value("예수금이 부족합니다"))
				.andExpect(jsonPath("$.detail.required").value(735000))
				.andExpect(jsonPath("$.detail.available").value(512000));
		}

		@Test
		@DisplayName("ORDER_INSUFFICIENT_QUANTITY 409")
		void insufficientQuantity() throws Exception {
			givenLoggedIn(42L);
			OrderReq sell = new OrderReq("005930", OrderSide.SELL, 10L);
			given(orderService.place(42L, sell)).willThrow(new CustomException(OrderErrorCode.ORDER_INSUFFICIENT_QUANTITY,
				Map.of("required", 10L, "available", 4L)));

			mockMvc.perform(place("{\"stockCode\":\"005930\",\"side\":\"SELL\",\"quantity\":10}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_INSUFFICIENT_QUANTITY"))
				.andExpect(jsonPath("$.detail.available").value(4));
		}

		/** 판정 순서의 첫 칸 — 열거값 밖은 도메인 코드가 아니라 공통 INVALID_REQUEST 다. 서비스에 닿지 않는다. */
		@Test
		@DisplayName("side 가 열거값 밖이면 400 INVALID_REQUEST 이고 서비스는 돌지 않는다")
		void rejectsUnknownSide() throws Exception {
			givenLoggedIn(42L);

			mockMvc.perform(place("{\"stockCode\":\"005930\",\"side\":\"HOLD\",\"quantity\":10}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			verifyNoInteractions(orderService);
		}

		@Test
		@DisplayName("필수 필드가 없으면 400 INVALID_REQUEST 이고 detail 에 필드명이 있다")
		void rejectsMissingFields() throws Exception {
			givenLoggedIn(42L);

			mockMvc.perform(place("{\"stockCode\":\"005930\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail.side").exists())
				.andExpect(jsonPath("$.detail.quantity").exists());
			mockMvc.perform(place("{\"side\":\"BUY\",\"quantity\":10}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail.stockCode").exists());

			verifyNoInteractions(orderService);
		}

		@Test
		@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다")
		void requiresAuthentication() throws Exception {
			mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(BUY_BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

			verifyNoInteractions(orderService);
		}
	}

	@Nested
	@DisplayName("GET /orders/available")
	class Available {

		@Test
		@DisplayName("200 과 apiSpec 7.3 의 본문 — tradable 이면 reason 은 null 이고 필드는 남는다")
		void returnsContractedBody() throws Exception {
			givenLoggedIn(42L);
			given(orderService.available(42L, "005930", OrderSide.BUY))
				.willReturn(OrderAvailableRes.tradable(73_500, 1_250_000, 17, 10));

			mockMvc.perform(authed(get("/api/v1/orders/available").param("stockCode", "005930").param("side", "BUY")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradable").value(true))
				.andExpect(jsonPath("$.reason").value(nullValue()))
				.andExpect(jsonPath("$.currentPrice").value(73500))
				.andExpect(jsonPath("$.availableCash").value(1250000))
				.andExpect(jsonPath("$.maxQuantity").value(17))
				.andExpect(jsonPath("$.holdingQuantity").value(10));
		}

		/** 거절은 에러가 아니라 200 의 reason 이다 (apiSpec 7.3). 화면은 이 값으로 버튼을 끈다. */
		@Test
		@DisplayName("tradable: false 는 HTTP 200 이고 reason 에 코드 문자열이 담긴다")
		void rejectionIsStillOk() throws Exception {
			givenLoggedIn(42L);
			given(orderService.available(42L, "005930", OrderSide.SELL))
				.willReturn(OrderAvailableRes.rejected(OrderErrorCode.ORDER_PRICE_UNAVAILABLE, null, 1_250_000, 0, 10));

			mockMvc.perform(authed(get("/api/v1/orders/available").param("stockCode", "005930").param("side", "SELL")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradable").value(false))
				.andExpect(jsonPath("$.reason").value("ORDER_PRICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.currentPrice").value(nullValue()))
				.andExpect(jsonPath("$.maxQuantity").value(0));
		}

		@Test
		@DisplayName("STOCK_NOT_FOUND 404 — 없는 종목만 에러다")
		void stockNotFound() throws Exception {
			givenLoggedIn(42L);
			given(orderService.available(42L, "999999", OrderSide.BUY))
				.willThrow(new CustomException(StockErrorCode.STOCK_NOT_FOUND));

			mockMvc.perform(authed(get("/api/v1/orders/available").param("stockCode", "999999").param("side", "BUY")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
		}

		@Test
		@DisplayName("side 가 열거값 밖이거나 파라미터가 없으면 400 INVALID_REQUEST 이고 detail 에 이름이 있다")
		void rejectsBadParameters() throws Exception {
			givenLoggedIn(42L);

			mockMvc.perform(authed(get("/api/v1/orders/available").param("stockCode", "005930").param("side", "HOLD")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail.side").exists());
			mockMvc.perform(authed(get("/api/v1/orders/available").param("side", "BUY")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail.stockCode").exists());

			verifyNoInteractions(orderService);
		}

		@Test
		@DisplayName("토큰이 없으면 401")
		void requiresAuthentication() throws Exception {
			mockMvc.perform(get("/api/v1/orders/available").param("stockCode", "005930").param("side", "BUY"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

			verifyNoInteractions(orderService);
		}
	}

	private MockHttpServletRequestBuilder place(String body) {
		return authed(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}
}
