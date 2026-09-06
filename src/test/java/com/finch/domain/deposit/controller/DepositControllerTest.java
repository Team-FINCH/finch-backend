package com.finch.domain.deposit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.deposit.dto.response.DepositLimitRes;
import com.finch.domain.deposit.dto.response.DepositReadyRes;
import com.finch.domain.deposit.dto.response.MockApproveRes;
import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.domain.deposit.exception.DepositErrorCode;
import com.finch.domain.deposit.exception.DepositRejectedException;
import com.finch.domain.deposit.gateway.MockScenario;
import com.finch.domain.deposit.service.DepositService;
import com.finch.global.config.SecurityConfig;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
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

/**
 * 충전 API 의 응답 계약(apiSpec §4)과 apiSpec 11.2 가 엔드포인트별로 정한 에러 코드를 고정한다.
 * <p>
 * {@code @WebMvcTest} 라 Docker 없이 돈다. 판정 <b>순서</b>와 DB 효과는 {@code DepositServiceTest} 가 보고,
 * 여기서는 서비스가 던진 코드가 그대로 나가는지와 요청 검증(열거값·필수값)이 어느 코드로 답하는지를 본다.
 */
@WebMvcTest(DepositController.class)
@Import(SecurityConfig.class)
class DepositControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DepositService depositService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Nested
	@DisplayName("GET /deposits/limit")
	class Limit {

		@Test
		@DisplayName("응답은 perRequestLimit · cumulativeLimit · depositedAmount · remainingAmount 네 개다")
		void returnsContractedBody() throws Exception {
			givenLoggedIn(42L);
			given(depositService.getLimit(42L)).willReturn(DepositLimitRes.of(10_000_000L, 100_000_000L, 3_000_000L));

			mockMvc.perform(get("/api/v1/deposits/limit").header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.perRequestLimit").value(10000000))
				.andExpect(jsonPath("$.cumulativeLimit").value(100000000))
				.andExpect(jsonPath("$.depositedAmount").value(3000000))
				.andExpect(jsonPath("$.remainingAmount").value(97000000))
				// v0.6 의 회차 기준 필드명이 되살아나면 프론트 파싱이 깨진다 (contracts C49).
				.andExpect(jsonPath("$.roundCumulativeLimit").doesNotExist());
		}

		@Test
		@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다")
		void requiresAuthentication() throws Exception {
			mockMvc.perform(get("/api/v1/deposits/limit"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

			verifyNoInteractions(depositService);
		}
	}

	@Nested
	@DisplayName("POST /deposits/ready")
	class Ready {

		@Test
		@DisplayName("201 과 paymentId · paymentMethod · amount · checkoutUrl · expiresAt(KST) 를 돌려준다")
		void returnsContractedBody() throws Exception {
			givenLoggedIn(42L);
			given(depositService.ready(42L, PaymentMethod.KAKAOPAY, 1_000_000L)).willReturn(
				DepositReadyRes.of(77L, PaymentMethod.KAKAOPAY, 1_000_000L,
					"https://online-pay.kakao.com/mockup/v1/abc/info", Instant.parse("2026-08-20T05:46:02Z")));

			mockMvc.perform(ready("""
					{"amount":1000000,"paymentMethod":"KAKAOPAY"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.paymentId").value(77))
				.andExpect(jsonPath("$.paymentMethod").value("KAKAOPAY"))
				.andExpect(jsonPath("$.amount").value(1000000))
				.andExpect(jsonPath("$.checkoutUrl").value("https://online-pay.kakao.com/mockup/v1/abc/info"))
				.andExpect(jsonPath("$.expiresAt").value("2026-08-20T14:46:02+09:00"));
		}

		/**
		 * 충전은 {@code Idempotency-Key} 를 쓰지 않는다 (apiSpec 1.4). 이 테스트는 컨트롤러 슬라이스라 멱등성 필터가
		 * 없지만, 헤더 없이도 서비스가 불린다는 것을 계약으로 남긴다. 필터 경로 목록은 {@code IdempotencyPropertiesTest} 가 본다.
		 */
		@Test
		@DisplayName("Idempotency-Key 없이 호출된다 — 충전의 멱등 기준은 paymentKey 다")
		void doesNotRequireIdempotencyKey() throws Exception {
			givenLoggedIn(42L);
			given(depositService.ready(anyLong(), any(), anyLong())).willReturn(
				DepositReadyRes.of(1L, PaymentMethod.TRANSFER, 1L, "http://front/deposit/transfer?paymentId=1",
					Instant.now()));

			mockMvc.perform(ready("""
					{"amount":1,"paymentMethod":"TRANSFER"}"""))
				.andExpect(status().isCreated());

			verify(depositService).ready(42L, PaymentMethod.TRANSFER, 1L);
		}

		/** 판정 1 — 열거값 밖은 도메인 코드가 아니라 공통 INVALID_REQUEST 다. 서비스에 닿지 않는다. */
		@Test
		@DisplayName("paymentMethod 가 열거값 밖이면 400 INVALID_REQUEST")
		void rejectsUnknownPaymentMethod() throws Exception {
			givenLoggedIn(42L);

			mockMvc.perform(ready("""
					{"amount":1000000,"paymentMethod":"VIRTUAL_CARD"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			verifyNoInteractions(depositService);
		}

		@Test
		@DisplayName("paymentMethod 가 없으면 400 INVALID_REQUEST 이고 detail 에 필드명이 있다")
		void rejectsMissingPaymentMethod() throws Exception {
			givenLoggedIn(42L);

			mockMvc.perform(ready("""
					{"amount":1000000}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail.paymentMethod").exists());

			verifyNoInteractions(depositService);
		}

		/** 판정 2 — 0 이하는 Bean Validation 이 아니라 서비스가 도메인 코드로 답한다. */
		@Test
		@DisplayName("금액 0 이하는 400 DEPOSIT_AMOUNT_INVALID")
		void amountInvalid() throws Exception {
			givenLoggedIn(42L);
			given(depositService.ready(42L, PaymentMethod.KAKAOPAY, 0L))
				.willThrow(new CustomException(DepositErrorCode.DEPOSIT_AMOUNT_INVALID));

			mockMvc.perform(ready("""
					{"amount":0,"paymentMethod":"KAKAOPAY"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("DEPOSIT_AMOUNT_INVALID"));
		}

		@Test
		@DisplayName("1회 한도 초과는 409 DEPOSIT_PER_REQUEST_LIMIT_EXCEEDED")
		void perRequestLimitExceeded() throws Exception {
			givenLoggedIn(42L);
			given(depositService.ready(42L, PaymentMethod.KAKAOPAY, 10_000_001L))
				.willThrow(new CustomException(DepositErrorCode.DEPOSIT_PER_REQUEST_LIMIT_EXCEEDED));

			mockMvc.perform(ready("""
					{"amount":10000001,"paymentMethod":"KAKAOPAY"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DEPOSIT_PER_REQUEST_LIMIT_EXCEEDED"));
		}

		/** 화면이 "잔여 한도: N원" 을 그리려면 detail.remainingAmount 가 있어야 한다 (featureSpec 3.3). */
		@Test
		@DisplayName("누적 한도 초과는 409 DEPOSIT_LIMIT_EXCEEDED 이고 detail.remainingAmount 를 싣는다")
		void cumulativeLimitExceeded() throws Exception {
			givenLoggedIn(42L);
			given(depositService.ready(42L, PaymentMethod.KAKAOPAY, 5_000_000L))
				.willThrow(new CustomException(DepositErrorCode.DEPOSIT_LIMIT_EXCEEDED,
					Map.of("remainingAmount", 3_000_000L)));

			mockMvc.perform(ready("""
					{"amount":5000000,"paymentMethod":"KAKAOPAY"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DEPOSIT_LIMIT_EXCEEDED"))
				.andExpect(jsonPath("$.detail.remainingAmount").value(3000000));
		}

		@Test
		@DisplayName("PG 가 준비를 거절하면 502 DEPOSIT_PG_UNAVAILABLE")
		void pgUnavailable() throws Exception {
			givenLoggedIn(42L);
			given(depositService.ready(42L, PaymentMethod.KAKAOPAY, 1_000_000L))
				.willThrow(new CustomException(DepositErrorCode.DEPOSIT_PG_UNAVAILABLE));

			mockMvc.perform(ready("""
					{"amount":1000000,"paymentMethod":"KAKAOPAY"}"""))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("DEPOSIT_PG_UNAVAILABLE"));
		}

		@Test
		@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다")
		void requiresAuthentication() throws Exception {
			mockMvc.perform(post("/api/v1/deposits/ready").contentType(MediaType.APPLICATION_JSON).content("""
					{"amount":1000000,"paymentMethod":"KAKAOPAY"}"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

			verifyNoInteractions(depositService);
		}

		private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder ready(String body) {
			return post("/api/v1/deposits/ready")
				.header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
		}
	}

	@Nested
	@DisplayName("GET /deposits/kakao/approval")
	class KakaoApproval {

		/** 카카오가 사용자의 브라우저를 여기로 보낸다. 토큰이 없어야 정상이고, 응답은 JSON 이 아니라 302 다. */
		@Test
		@DisplayName("무인증으로 호출되고 서비스가 준 URL 로 302 한다")
		void redirectsWithoutAuthentication() throws Exception {
			given(depositService.kakaoApproval(77L, "pg-token"))
				.willReturn("http://localhost:5173/deposit/complete?paymentId=77&paymentKey=A1&amount=1000000");

			mockMvc.perform(get("/api/v1/deposits/kakao/approval")
					.param("paymentId", "77")
					.param("pg_token", "pg-token"))
				.andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION,
					"http://localhost:5173/deposit/complete?paymentId=77&paymentKey=A1&amount=1000000"));
		}

		/** 실패해도 본문 에러(§1.3)가 아니다 — 브라우저가 보는 응답이라 JSON 을 보여줄 화면이 없다. */
		@Test
		@DisplayName("실패도 본문 에러가 아니라 실패 URL 로 302 한다")
		void redirectsOnFailure() throws Exception {
			given(depositService.kakaoApproval(77L, "pg-token"))
				.willReturn("http://localhost:5173/deposit/fail?paymentId=77&code=DEPOSIT_PG_UNAVAILABLE");

			mockMvc.perform(get("/api/v1/deposits/kakao/approval")
					.param("paymentId", "77")
					.param("pg_token", "pg-token"))
				.andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION,
					"http://localhost:5173/deposit/fail?paymentId=77&code=DEPOSIT_PG_UNAVAILABLE"));
		}

		@Test
		@DisplayName("pg_token 이 없어도 400 이 아니라 서비스에 넘긴다 — 판정은 서비스가 하고 답은 302 다")
		void passesMissingTokenToService() throws Exception {
			given(depositService.kakaoApproval(77L, null))
				.willReturn("http://localhost:5173/deposit/fail?paymentId=77&code=DEPOSIT_PAYMENT_FAILED");

			mockMvc.perform(get("/api/v1/deposits/kakao/approval").param("paymentId", "77"))
				.andExpect(status().isFound());

			verify(depositService).kakaoApproval(77L, null);
		}
	}

	@Nested
	@DisplayName("POST /deposits/{paymentId}/mock-approve")
	class MockApprove {

		@Test
		@DisplayName("본문 없이 부르면 SUCCESS 시나리오이고 paymentId · paymentKey · amount 를 돌려준다")
		void defaultsToSuccessScenario() throws Exception {
			givenLoggedIn(42L);
			given(depositService.mockApprove(42L, 77L, MockScenario.SUCCESS))
				.willReturn(new MockApproveRes(77L, "mock_pk_9f2c", 1_000_000L));

			mockMvc.perform(post("/api/v1/deposits/77/mock-approve")
					.header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentId").value(77))
				.andExpect(jsonPath("$.paymentKey").value("mock_pk_9f2c"))
				.andExpect(jsonPath("$.amount").value(1000000));
		}

		@Test
		@DisplayName("scenario 를 보내면 그대로 서비스에 넘긴다 — 실패 시나리오는 409 DEPOSIT_PAYMENT_FAILED")
		void passesScenario() throws Exception {
			givenLoggedIn(42L);
			given(depositService.mockApprove(42L, 77L, MockScenario.TIMEOUT))
				.willThrow(new DepositRejectedException(DepositErrorCode.DEPOSIT_PAYMENT_FAILED));

			mockMvc.perform(post("/api/v1/deposits/77/mock-approve")
					.header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"scenario\":\"TIMEOUT\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DEPOSIT_PAYMENT_FAILED"));
		}

		@Test
		@DisplayName("scenario 가 열거값 밖이면 400 INVALID_REQUEST")
		void rejectsUnknownScenario() throws Exception {
			givenLoggedIn(42L);

			mockMvc.perform(post("/api/v1/deposits/77/mock-approve")
					.header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"scenario\":\"EXPLODE\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			verifyNoInteractions(depositService);
		}

		@Test
		@DisplayName("없는 건이거나 내 것이 아니면 404 DEPOSIT_NOT_FOUND")
		void notFound() throws Exception {
			givenLoggedIn(42L);
			given(depositService.mockApprove(42L, 999L, MockScenario.SUCCESS))
				.willThrow(new CustomException(DepositErrorCode.DEPOSIT_NOT_FOUND));

			mockMvc.perform(post("/api/v1/deposits/999/mock-approve")
					.header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("DEPOSIT_NOT_FOUND"));
		}

		@Test
		@DisplayName("KAKAOPAY 건에 부르면 409 DEPOSIT_INVALID_STATE")
		void invalidState() throws Exception {
			givenLoggedIn(42L);
			given(depositService.mockApprove(42L, 77L, MockScenario.SUCCESS))
				.willThrow(new CustomException(DepositErrorCode.DEPOSIT_INVALID_STATE));

			mockMvc.perform(post("/api/v1/deposits/77/mock-approve")
					.header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DEPOSIT_INVALID_STATE"));
		}

		@Test
		@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다")
		void requiresAuthentication() throws Exception {
			mockMvc.perform(post("/api/v1/deposits/77/mock-approve"))
				.andExpect(status().isUnauthorized());

			verifyNoInteractions(depositService);
		}
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
