package com.finch.domain.auth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.auth.dto.response.AuthUserRes;
import com.finch.domain.auth.dto.response.KakaoLoginRes;
import com.finch.domain.auth.service.LoginResult;
import com.finch.domain.auth.service.TestLoginService;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.config.SecurityConfig;
import com.finch.global.exception.CustomException;
import com.finch.global.security.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 테스트 로그인이 켜져 있을 때의 HTTP 계약 (apiSpec 2.5) — 토큰 없이 들어오고, 카카오 로그인과 같은 본문과 Refresh 쿠키를 준다.
 * 판정 규칙은 {@code TestLoginServiceTest} 가, 꺼져 있을 때는 {@link TestLoginDisabledTest} 가 본다.
 */
@WebMvcTest(controllers = TestLoginController.class, properties = "finch.auth.test-login.enabled=true")
@Import(SecurityConfig.class)
class TestLoginControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TestLoginService testLoginService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("토큰 없이 들어오고, 헤더 키와 번호를 서비스로 넘기며, 카카오 로그인과 같은 본문 + Refresh 쿠키를 준다")
	void returnsLoginBodyAndRefreshCookie() throws Exception {
		given(testLoginService.login("the-key", 2)).willReturn(new LoginResult(
			new KakaoLoginRes("access-token", true, new AuthUserRes(7L, "테스트 사용자 2", null)), "refresh-token"));

		mockMvc.perform(post("/api/v1/auth/test-login")
				.header(TestLoginController.KEY_HEADER, "the-key")
				.contentType(MediaType.APPLICATION_JSON).content("{\"testUserNo\":2}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("access-token"))
			.andExpect(jsonPath("$.isNewUser").value(true))
			.andExpect(jsonPath("$.user.userId").value(7))
			.andExpect(jsonPath("$.user.nickname").value("테스트 사용자 2"))
			// 속성까지 카카오 로그인과 같아야 재발급·로그아웃이 이 쿠키를 알아본다.
			.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("refreshToken=refresh-token"),
				containsString("HttpOnly"), containsString("Secure"), containsString("Path=/api/v1/auth"),
				containsString("SameSite=Lax"))));
	}

	@Test
	@DisplayName("키가 틀리면 서비스가 던진 404 RESOURCE_NOT_FOUND 가 그대로 나간다 — 꺼져 있을 때와 같은 응답")
	void wrongKeyIsNotFound() throws Exception {
		given(testLoginService.login(any(), any())).willThrow(new CustomException(GeneralErrorCode.RESOURCE_NOT_FOUND));

		mockMvc.perform(post("/api/v1/auth/test-login")
				.contentType(MediaType.APPLICATION_JSON).content("{\"testUserNo\":1}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}

	/** 형식 검사를 컨트롤러에서 하지 않는다 — 본문이 없어도 서비스까지 가서 키부터 대조한다. */
	@Test
	@DisplayName("본문이 없어도 400 이 아니라 서비스로 간다 (번호 null)")
	void missingBodyReachesService() throws Exception {
		given(testLoginService.login(anyString(), any())).willThrow(new CustomException(GeneralErrorCode.RESOURCE_NOT_FOUND));

		mockMvc.perform(post("/api/v1/auth/test-login").header(TestLoginController.KEY_HEADER, "k"))
			.andExpect(status().isNotFound());

		verify(testLoginService).login("k", null);
	}
}
