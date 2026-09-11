package com.finch.domain.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.global.config.SecurityConfig;
import com.finch.global.security.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 기본(꺼짐) 상태에서는 테스트 로그인 경로가 <b>없다</b> (apiSpec 2.5). 인증 규칙에서는 열려 있어도 컨트롤러가 만들어지지 않아
 * 404 {@code RESOURCE_NOT_FOUND} 다 — 키가 틀렸을 때와 같은 응답이다.
 */
@WebMvcTest(controllers = TestLoginController.class)
@Import(SecurityConfig.class)
class TestLoginDisabledTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("설정이 꺼져 있으면 키를 보내도 404 RESOURCE_NOT_FOUND 다")
	void pathDoesNotExist() throws Exception {
		mockMvc.perform(post("/api/v1/auth/test-login")
				.header(TestLoginController.KEY_HEADER, "test-login-key-0123456789")
				.contentType(MediaType.APPLICATION_JSON).content("{\"testUserNo\":1}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}
}
