package com.finch.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.auth.dto.request.KakaoLoginReq;
import com.finch.domain.auth.dto.response.AuthUserRes;
import com.finch.domain.auth.dto.response.KakaoLoginRes;
import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.domain.auth.service.AuthService;
import com.finch.domain.auth.service.LoginResult;
import com.finch.domain.auth.service.TokenPair;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import com.finch.global.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * apiSpec 2.1 의 응답 계약을 고정한다. 필드 이름 하나가 달라지면 프론트의 Zod 파싱이 깨지므로
 * 값이 아니라 **이름과 위치**를 보는 테스트다.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

	private static final String REQUEST_JSON = """
		{"authorizationCode":"code","redirectUri":"http://localhost:5173/oauth/kakao"}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("로그인 응답 본문은 accessToken · isNewUser · user 세 개다")
	void returnsContractedBody() throws Exception {
		given(authService.loginWithKakao(any(KakaoLoginReq.class))).willReturn(loginResult("https://img.kakao/1.jpg"));

		mockMvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("access-token"))
			// isNewUser 는 boolean 이라 Jackson 이 "newUser" 로 깎을 수 있다. 이름을 그대로 못 박는다.
			.andExpect(jsonPath("$.isNewUser").value(true))
			.andExpect(jsonPath("$.user.userId").value(1))
			.andExpect(jsonPath("$.user.nickname").value("홍길동"))
			.andExpect(jsonPath("$.user.profileImageUrl").value("https://img.kakao/1.jpg"));
	}

	@Test
	@DisplayName("Refresh Token 은 본문에 없고 Set-Cookie 로만 나간다")
	void sendsRefreshTokenOnlyAsCookie() throws Exception {
		given(authService.loginWithKakao(any(KakaoLoginReq.class))).willReturn(loginResult(null));

		MvcResult result = mockMvc.perform(
				post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andReturn();

		String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).contains("refreshToken=refresh-token")
			.contains("HttpOnly")
			.contains("Secure")
			.contains("SameSite=Lax")
			.contains("Path=/api/v1/auth")
			// 14일. apiSpec 2.1 의 Max-Age=1209600 과 같은 값이어야 한다.
			.contains("Max-Age=1209600");
	}

	@Test
	@DisplayName("프로필 사진이 없으면 본문에 null 로 나간다 — 필드를 빼지 않는다")
	void keepsNullProfileImageField() throws Exception {
		given(authService.loginWithKakao(any(KakaoLoginReq.class))).willReturn(loginResult(null));

		mockMvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.profileImageUrl").isEmpty());
	}

	@Test
	@DisplayName("인가 코드가 비어 있으면 서비스를 부르지 않고 400 이다")
	void rejectsBlankAuthorizationCode() throws Exception {
		String blank = """
			{"authorizationCode":"","redirectUri":"http://localhost:5173/oauth/kakao"}""";

		mockMvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON).content(blank))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("재발급은 본문에 accessToken 만 주고 새 Refresh 는 쿠키로 내려간다")
	void refreshReturnsAccessTokenAndRotatesCookie() throws Exception {
		given(authService.refresh("old-refresh")).willReturn(new TokenPair("new-access", "new-refresh"));

		MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refreshToken", "old-refresh")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("new-access"))
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andReturn();

		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
			.contains("refreshToken=new-refresh")
			.contains("Max-Age=1209600");
	}

	@Test
	@DisplayName("쿠키가 없으면 AUTH_REFRESH_TOKEN_MISSING — 최초 방문자를 로그인 화면으로 튕기지 않으려는 구분이다")
	void missingCookieIsItsOwnCode() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_MISSING"));

		// 쿠키 유무는 HTTP 의 일이라 서비스까지 내려가지 않는다.
		verifyNoInteractions(authService);
	}

	/**
	 * 이 테스트가 백5 의 필터 설계를 못 박는다. 프론트 인터셉터는 <b>만료된 Access 를 붙인 채로</b>
	 * 재발급을 부른다 (apiSpec 1.2). 필터가 헤더를 보고 직접 401 을 쓰면 재발급 자체가 불가능해져
	 * 세션 복구가 영구히 깨진다 — 그래서 필터는 거부하지 않고 EntryPoint 가 인증 필요 경로에서만 판단한다.
	 */
	@Test
	@DisplayName("만료된 Access 가 실려 와도 재발급은 막히지 않는다 — 판정 기준은 쿠키뿐이다")
	void refreshIgnoresTheAuthorizationHeader() throws Exception {
		given(jwtProvider.parseAccessToken("expired")).willThrow(
			new CustomException(AuthErrorCode.AUTH_TOKEN_EXPIRED));
		given(authService.refresh("old-refresh")).willReturn(new TokenPair("new-access", "new-refresh"));

		mockMvc.perform(post("/api/v1/auth/refresh")
				.header(HttpHeaders.AUTHORIZATION, "Bearer expired")
				.cookie(new Cookie("refreshToken", "old-refresh")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("new-access"));
	}

	@Test
	@DisplayName("로그아웃은 204 이고 브라우저 쿠키도 즉시 만료시킨다")
	void logoutClearsCookie() throws Exception {
		given(jwtProvider.parseAccessToken("access-token")).willReturn(7L);

		MvcResult result = mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
			.andExpect(status().isNoContent())
			.andReturn();

		verify(authService).logout(7L);
		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
			.contains("refreshToken=")
			.contains("Max-Age=0");
	}

	@Test
	@DisplayName("Authorization 헤더가 없으면 로그아웃도 AUTH_INVALID_TOKEN — 화이트리스트 밖이라 필터 체인에서 걸린다")
	void logoutWithoutBearerIsInvalidToken() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		verifyNoInteractions(authService);
	}

	private LoginResult loginResult(String profileImageUrl) {
		return new LoginResult(
			new KakaoLoginRes("access-token", true, new AuthUserRes(1L, "홍길동", profileImageUrl)),
			"refresh-token");
	}
}
