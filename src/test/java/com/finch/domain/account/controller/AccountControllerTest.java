package com.finch.domain.account.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.account.dto.response.AccountRes;
import com.finch.domain.account.service.AccountService;
import com.finch.global.config.SecurityConfig;
import com.finch.global.security.JwtProvider;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * `GET /account` 의 응답 계약(apiSpec 3.1)과 인증 배선을 고정한다.
 * <p>
 * {@code @WebMvcTest} 라 Docker 없이 돈다. 필터 체인은 실제 {@code SecurityConfig} 를 그대로 쓰고
 * {@code JwtProvider} 만 목이다 — {@code UserControllerTest} 와 같은 방식이다.
 */
@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
class AccountControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccountService accountService;

	@MockitoBean
	private JwtProvider jwtProvider;

	/**
	 * 필드 이름 하나가 달라지면 프론트의 파싱이 깨진다. 특히 {@code totalAsset} 은 서버가 더해서
	 * 내려주는 값이라(featureSpec 9.3) 빠지면 프론트가 직접 더하기 시작한다.
	 */
	@Test
	@DisplayName("응답은 cashBalance · evaluationAmount · totalAsset · asOf 네 개다")
	void returnsContractedBody() throws Exception {
		givenLoggedIn(42L);
		given(accountService.getSummary(42L)).willReturn(
			AccountRes.of(1_250_000L, 735_000L, Instant.parse("2026-08-20T05:30:00Z")));

		mockMvc.perform(get("/api/v1/account").header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cashBalance").value(1250000))
			.andExpect(jsonPath("$.evaluationAmount").value(735000))
			.andExpect(jsonPath("$.totalAsset").value(1985000))
			// apiSpec 1.1 — KST 오프셋을 포함한다. Z 로 나가면 화면의 갱신 시각이 9시간 밀린다.
			.andExpect(jsonPath("$.asOf").value("2026-08-20T14:30:00+09:00"));
	}

	/** 계좌는 사용자당 하나라 클라이언트가 지목할 대상이 아니다 (apiSpec 1.6). */
	@Test
	@DisplayName("응답에 계좌 식별자를 넣지 않는다")
	void omitsAccountIdentifier() throws Exception {
		givenLoggedIn(42L);
		given(accountService.getSummary(42L)).willReturn(AccountRes.of(0L, 0L, Instant.now()));

		mockMvc.perform(get("/api/v1/account").header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accountId").doesNotExist());
	}

	/** 초기 지급이 없어져 가입 직후가 이 상태다. 0 은 오류가 아니라 정상 초기 상태다 (featureSpec 2.2). */
	@Test
	@DisplayName("갓 가입한 계정은 잔고 0 · 총자산 0 을 정상 응답으로 받는다")
	void returnsZeroBalanceForNewAccount() throws Exception {
		givenLoggedIn(42L);
		given(accountService.getSummary(42L)).willReturn(AccountRes.of(0L, 0L, Instant.now()));

		mockMvc.perform(get("/api/v1/account").header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cashBalance").value(0))
			.andExpect(jsonPath("$.totalAsset").value(0));
	}

	@Test
	@DisplayName("조회 대상은 토큰이 가리키는 사용자다 — 요청이 정한 값이 아니다")
	void usesUserIdFromToken() throws Exception {
		givenLoggedIn(7L);
		given(accountService.getSummary(7L)).willReturn(AccountRes.of(0L, 0L, Instant.now()));

		mockMvc.perform(get("/api/v1/account").header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
			.andExpect(status().isOk());

		verify(accountService).getSummary(7L);
	}

	@Test
	@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/account"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		verifyNoInteractions(accountService);
	}

	/**
	 * 경로는 있고 메서드가 없는 경우다. 401 이 아니라 405 여야 한다 (apiSpec 11.1) —
	 * 401 로 답하면 프론트가 토큰 문제로 읽고 재발급을 시도한다.
	 */
	@Test
	@DisplayName("POST /account 는 405 METHOD_NOT_ALLOWED")
	void rejectsUnsupportedMethod() throws Exception {
		givenLoggedIn(42L);

		mockMvc.perform(post("/api/v1/account").header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
