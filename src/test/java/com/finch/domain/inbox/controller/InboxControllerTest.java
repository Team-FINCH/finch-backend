package com.finch.domain.inbox.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.inbox.dto.response.InboxRes;
import com.finch.domain.inbox.service.InboxService;
import com.finch.global.config.SecurityConfig;
import com.finch.global.security.JwtProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 알림함의 응답 계약(apiSpec 6.4)과 경로 검증을 고정한다. {@code @WebMvcTest} 라 Docker 없이 돈다. 항목 계산은
 * {@code InboxServiceTest} 가 본다.
 */
@WebMvcTest(InboxController.class)
@Import(SecurityConfig.class)
class InboxControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private InboxService inboxService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("unreadCount 와 items 아홉 필드 — kind 는 소문자, createdAt 은 KST, tradeId 는 숫자")
	void returnsContractedBody() throws Exception {
		givenLoggedIn(42L);
		given(inboxService.list(42L)).willReturn(InboxRes.of(List.of(InboxRes.Item.record("record-000660-101", "000660",
			"SK하이닉스", 101L, Instant.parse("2026-09-11T00:31:00Z"), true))));

		mockMvc.perform(authed(get("/api/v1/inbox")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unreadCount").value(1))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].itemId").value("record-000660-101"))
			.andExpect(jsonPath("$.items[0].kind").value("record"))
			.andExpect(jsonPath("$.items[0].title").value("SK하이닉스, 왜 담으셨나요?"))
			.andExpect(jsonPath("$.items[0].summary").isString())
			.andExpect(jsonPath("$.items[0].unread").value(true))
			.andExpect(jsonPath("$.items[0].createdAt").value("2026-09-11T09:31:00+09:00"))
			.andExpect(jsonPath("$.items[0].stockCode").value("000660"))
			.andExpect(jsonPath("$.items[0].stockName").value("SK하이닉스"))
			.andExpect(jsonPath("$.items[0].tradeId").value(101));
	}

	@Test
	@DisplayName("항목이 없으면 unreadCount 0 에 빈 items — 에러가 아니다")
	void emptyIsNotAnError() throws Exception {
		givenLoggedIn(42L);
		given(inboxService.list(42L)).willReturn(InboxRes.empty());

		mockMvc.perform(authed(get("/api/v1/inbox")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unreadCount").value(0))
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	@DisplayName("읽음 표시는 204 이고 본문이 없다")
	void markReadReturnsNoContent() throws Exception {
		givenLoggedIn(42L);

		mockMvc.perform(authed(post("/api/v1/inbox/record-000660-101/read")))
			.andExpect(status().isNoContent())
			.andExpect(jsonPath("$").doesNotExist());

		verify(inboxService).markRead(42L, "record-000660-101");
	}

	@Test
	@DisplayName("itemId 가 64자를 넘으면 400 INVALID_REQUEST 이고 detail 에 itemId — 64자 정확히는 통과한다")
	void rejectsTooLongItemId() throws Exception {
		givenLoggedIn(42L);

		mockMvc.perform(authed(post("/api/v1/inbox/" + "a".repeat(64) + "/read")))
			.andExpect(status().isNoContent());
		mockMvc.perform(authed(post("/api/v1/inbox/" + "a".repeat(65) + "/read")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.itemId").exists());

		verify(inboxService, never()).markRead(anyLong(), eq("a".repeat(65)));
	}

	@Test
	@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/inbox"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
		mockMvc.perform(post("/api/v1/inbox/record-000660-101/read"))
			.andExpect(status().isUnauthorized());

		verifyNoInteractions(inboxService);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}
}
