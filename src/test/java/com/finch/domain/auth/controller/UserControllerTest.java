package com.finch.domain.auth.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.auth.dto.response.UserMeRes;
import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.domain.auth.service.UserService;
import com.finch.global.config.SecurityConfig;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 인증이 걸린 첫 엔드포인트다. 두 가지를 함께 고정한다.
 * <ul>
 *   <li>apiSpec 2.4 의 응답 계약 — 필드 이름 하나가 달라지면 프론트의 Zod 파싱이 깨진다.</li>
 *   <li><b>인증 실패 코드의 분기</b> — 프론트 인터셉터는 {@code AUTH_TOKEN_EXPIRED} 에서만 재발급을
 *       시도하고 {@code AUTH_INVALID_TOKEN} 에서는 로그인 화면으로 보낸다 (apiSpec 1.2).
 *       두 코드가 뒤바뀌면 무한 재발급 루프이거나, 반대로 로그인이 30분마다 풀린다.</li>
 * </ul>
 * {@code @WebMvcTest} 라 Docker 없이 돈다. 필터 체인은 {@code @Import(SecurityConfig.class)} 로
 * 실제 것을 그대로 쓰고 {@code JwtProvider} 만 목이다 — 검사대의 배선을 보는 것이 목적이다.
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserService userService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("내 정보 응답은 userId · nickname · profileImageUrl · joinedAt 네 개다")
	void returnsContractedBody() throws Exception {
		givenLoggedIn(42L);
		given(userService.getMe(42L)).willReturn(userMeRes(42L));

		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(42))
			.andExpect(jsonPath("$.nickname").value("홍길동"))
			.andExpect(jsonPath("$.profileImageUrl").value("https://img.kakao/1.jpg"))
			// apiSpec 1.1 — 시각은 KST 오프셋을 포함한다. Instant 를 그대로 내보내면 Z 로 나가 계약과 다르다.
			.andExpect(jsonPath("$.joinedAt").value("2026-08-25T10:00:00+09:00"));
	}

	/**
	 * 투자 회차가 없어지면서 `currentRoundId` 도 빠졌다 (GitLab 이슈 #27). 계좌는 사용자당 하나라
	 * 클라이언트가 식별자로 지목할 대상이 아니다 — 계좌 도메인이 붙을 때 `accountId` 로 되살리지 않는다.
	 */
	@Test
	@DisplayName("응답에 계좌·회차 식별자를 넣지 않는다")
	void omitsAccountIdentifier() throws Exception {
		givenLoggedIn(42L);
		given(userService.getMe(42L)).willReturn(userMeRes(42L));

		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentRoundId").doesNotExist())
			.andExpect(jsonPath("$.accountId").doesNotExist());
	}

	@Test
	@DisplayName("@LoginUser 로 들어오는 userId 는 토큰이 가리키는 사용자다 — 요청이 정한 값이 아니다")
	void passesUserIdFromToken() throws Exception {
		givenLoggedIn(7L);
		given(userService.getMe(7L)).willReturn(userMeRes(7L));

		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(VALID_TOKEN)))
			.andExpect(status().isOk());

		verify(userService).getMe(7L);
	}

	@Test
	@DisplayName("Authorization 헤더가 없으면 AUTH_INVALID_TOKEN — 누락을 만료로 주면 붙이지 않은 버그가 가려진다")
	void missingHeaderIsInvalidToken() throws Exception {
		mockMvc.perform(get("/api/v1/users/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		verifyNoInteractions(userService);
	}

	@Test
	@DisplayName("Bearer 형식이 아니면 AUTH_INVALID_TOKEN")
	void malformedHeaderIsInvalidToken() throws Exception {
		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, VALID_TOKEN))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		verifyNoInteractions(userService);
	}

	@Test
	@DisplayName("만료된 토큰은 AUTH_TOKEN_EXPIRED — 프론트 인터셉터가 이 코드에서만 재발급한다")
	void expiredTokenKeepsItsOwnCode() throws Exception {
		given(jwtProvider.parseAccessToken("expired"))
			.willThrow(new CustomException(AuthErrorCode.AUTH_TOKEN_EXPIRED));

		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer("expired")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_TOKEN_EXPIRED"));

		verifyNoInteractions(userService);
	}

	@Test
	@DisplayName("서명이 틀린 토큰은 AUTH_TOKEN_EXPIRED 가 아니다 — 재발급해도 소용없으므로 로그인으로 보낸다")
	void tamperedTokenIsInvalidNotExpired() throws Exception {
		given(jwtProvider.parseAccessToken("tampered"))
			.willThrow(new CustomException(AuthErrorCode.AUTH_INVALID_TOKEN));

		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer("tampered")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	/**
	 * 401 을 쓰는 곳이 EntryPoint 와 GlobalExceptionHandler 두 군데인데 모양이 갈리면
	 * 프론트가 401 본문을 두 가지로 파싱해야 한다. 같은 {@code ErrorResponse} 를 쓰는지 본다 (apiSpec 1.3).
	 */
	@Test
	@DisplayName("인증 실패 본문도 다른 에러와 같은 형식이다 — code · message 가 있고 detail 은 없다")
	void authFailureUsesTheSameErrorBody() throws Exception {
		mockMvc.perform(get("/api/v1/users/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"))
			.andExpect(jsonPath("$.message").value(AuthErrorCode.AUTH_INVALID_TOKEN.getMessage()))
			.andExpect(jsonPath("$.detail").doesNotExist())
			// 인증 실패도 추적 대상이다. RequestIdFilter 는 Security 보다 앞이라 헤더가 남아야 한다.
			.andExpect(header().exists("X-Request-Id"));
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static UserMeRes userMeRes(long userId) {
		return new UserMeRes(userId, "홍길동", "https://img.kakao/1.jpg",
			OffsetDateTime.of(2026, 8, 25, 10, 0, 0, 0, ZoneOffset.ofHours(9)));
	}
}
