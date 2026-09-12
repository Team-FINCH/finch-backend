package com.finch.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.repository.AccountRepository;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.global.security.JwtProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 켜 둔 앱을 통째로 띄워 테스트 로그인이 <b>카카오 로그인과 같은 결과</b>를 남기는지 본다 (apiSpec 2.5) — 음수 kakaoId 계정과 계좌가
 * 생기고, 받은 Access 로 인증 API 가 열리고, 받은 Refresh 쿠키로 재발급이 된다. 프론트의 세션 복구가 이 재발급을 쓴다.
 */
@SpringBootTest(properties = {
	"finch.auth.test-login.enabled=true",
	"finch.auth.test-login.key=integration-test-login-key"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TestLoginIntegrationTest {

	private static final String KEY = "integration-test-login-key";

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("음수 kakaoId 계정과 계좌를 만들고, Access 로 인증 API 가 열리며, Refresh 쿠키로 재발급된다. 두 번째는 같은 계정이다")
	void behavesLikeKakaoLogin() throws Exception {
		MvcResult first = login(1);
		JsonNode body = mapper.readTree(first.getResponse().getContentAsString());
		long userId = body.get("user").get("userId").asLong();

		User user = userRepository.findById(userId).orElseThrow();
		assertThat(user.getKakaoId()).isEqualTo(-1L);
		assertThat(user.getNickname()).isEqualTo("FINCH 시연 계정");
		assertThat(accountRepository.findByUserId(userId)).isPresent();
		assertThat(jwtProvider.parseAccessToken(body.get("accessToken").asString())).isEqualTo(userId);

		mockMvc.perform(get("/api/v1/users/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + body.get("accessToken").asString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nickname").value("FINCH 시연 계정"));

		Cookie refresh = first.getResponse().getCookie("refreshToken");
		assertThat(refresh).isNotNull();
		mockMvc.perform(post("/api/v1/auth/refresh").cookie(refresh))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isString());

		JsonNode second = mapper.readTree(login(1).getResponse().getContentAsString());
		assertThat(second.get("user").get("userId").asLong()).isEqualTo(userId);
		assertThat(second.get("isNewUser").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("키가 틀리면 404 이고 계정을 만들지 않는다")
	void wrongKeyCreatesNothing() throws Exception {
		mockMvc.perform(post("/api/v1/auth/test-login").header(TestLoginController.KEY_HEADER, "wrong-key-0123456789")
				.contentType(MediaType.APPLICATION_JSON).content("{\"testUserNo\":2}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

		assertThat(userRepository.findByKakaoId(-2L)).isEmpty();
	}

	private MvcResult login(int testUserNo) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/test-login").header(TestLoginController.KEY_HEADER, KEY)
				.contentType(MediaType.APPLICATION_JSON).content("{\"testUserNo\":" + testUserNo + "}"))
			.andExpect(status().isOk())
			.andReturn();
	}
}
