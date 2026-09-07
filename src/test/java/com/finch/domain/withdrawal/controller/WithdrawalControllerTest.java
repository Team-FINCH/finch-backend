package com.finch.domain.withdrawal.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.withdrawal.dto.response.WithdrawalRes;
import com.finch.domain.withdrawal.exception.WithdrawalErrorCode;
import com.finch.domain.withdrawal.service.WithdrawalService;
import com.finch.global.config.SecurityConfig;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import java.time.Instant;
import java.util.Map;
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
 * 출금 API 의 응답 계약(apiSpec 4.5)과 apiSpec 11.2 가 이 엔드포인트에 정한 에러 코드 2종을 고정한다.
 * <p>
 * {@code @WebMvcTest} 라 Docker 없이 돌고 <b>멱등성 필터는 여기 없다</b> ({@code IdempotencyConfig} 주석). 그래서
 * {@code Idempotency-Key} 누락 400 과 재전송 재생은 여기서 보지 않고 {@code WithdrawalIdempotencyTest} 가 실제 필터
 * 체인으로 본다. 판정 <b>순서</b>와 DB 효과는 {@code WithdrawalServiceTest} 가 본다.
 */
@WebMvcTest(WithdrawalController.class)
@Import(SecurityConfig.class)
class WithdrawalControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";
	private static final String BODY = "{\"amount\":500000}";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private WithdrawalService withdrawalService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("201 과 withdrawalId · amount · cashBalanceAfter · withdrawnAt(KST) 를 돌려준다")
	void returnsContractedBody() throws Exception {
		givenLoggedIn(42L);
		given(withdrawalService.withdraw(42L, 500_000L))
			.willReturn(WithdrawalRes.of(12L, 500_000L, 750_000L, Instant.parse("2026-08-20T06:02:11Z")));

		mockMvc.perform(withdraw(BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.withdrawalId").value(12))
			.andExpect(jsonPath("$.amount").value(500000))
			.andExpect(jsonPath("$.cashBalanceAfter").value(750000))
			.andExpect(jsonPath("$.withdrawnAt").value("2026-08-20T15:02:11+09:00"))
			// 출금 수단·한도 필드는 계약에 없다 (apiSpec 4.5). 되살아나면 프론트가 헛구현한다.
			.andExpect(jsonPath("$.paymentMethod").doesNotExist());
	}

	/** 0 이하는 Bean Validation 이 아니라 서비스 판정이다 (판정 2). {@code INVALID_REQUEST} 가 아니어야 프론트 분기가 맞는다. */
	@Test
	@DisplayName("금액 0 이하는 400 WITHDRAWAL_AMOUNT_INVALID — 서비스 판정이고 INVALID_REQUEST 가 아니다")
	void amountInvalid() throws Exception {
		givenLoggedIn(42L);
		given(withdrawalService.withdraw(42L, 0L))
			.willThrow(new CustomException(WithdrawalErrorCode.WITHDRAWAL_AMOUNT_INVALID));

		mockMvc.perform(withdraw("{\"amount\":0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_AMOUNT_INVALID"));
	}

	@Test
	@DisplayName("예수금 부족은 409 WITHDRAWAL_INSUFFICIENT_CASH 이고 detail.availableAmount 를 싣는다")
	void insufficientCash() throws Exception {
		givenLoggedIn(42L);
		given(withdrawalService.withdraw(42L, 500_000L))
			.willThrow(new CustomException(WithdrawalErrorCode.WITHDRAWAL_INSUFFICIENT_CASH,
				Map.of("availableAmount", 120_000L)));

		mockMvc.perform(withdraw(BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_INSUFFICIENT_CASH"))
			.andExpect(jsonPath("$.detail.availableAmount").value(120000));
	}

	@Test
	@DisplayName("amount 가 없으면 400 INVALID_REQUEST 이고 detail 에 필드명이 있다 — 서비스는 돌지 않는다")
	void amountMissing() throws Exception {
		givenLoggedIn(42L);

		mockMvc.perform(withdraw("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.amount").exists());

		verifyNoInteractions(withdrawalService);
	}

	@Test
	@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다 — 새 엔드포인트는 기본으로 보호된다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/withdrawals").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		verifyNoInteractions(withdrawalService);
	}

	private MockHttpServletRequestBuilder withdraw(String body) {
		return post("/api/v1/withdrawals")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}
}
