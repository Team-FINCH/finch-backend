package com.finch.domain.ledger.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.domain.ledger.dto.request.TransactionFilter;
import com.finch.domain.ledger.dto.response.TransactionRes;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.service.TransactionQueryService;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.config.SecurityConfig;
import com.finch.global.exception.CustomException;
import com.finch.global.paging.CursorPage;
import com.finch.global.security.JwtProvider;
import com.finch.global.util.KstTime;
import java.math.BigDecimal;
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
 * 매매 내역 API 의 응답 계약(apiSpec 8.2·1.5)과 파라미터 검증이 전부 {@code INVALID_REQUEST} 로 답하는지 고정한다.
 * <p>
 * {@code @WebMvcTest} 라 Docker 없이 돈다. 필터·정렬·페이징의 실제 동작은 {@code TransactionQueryServiceTest} 가 DB 로 본다.
 * 여기서는 (1) 유형별로 null 인 필드가 <b>키째 빠지지 않고 null 로 내려가는지</b>, (2) 파라미터 기본값·범위·열거값이
 * 어느 코드로 답하는지를 본다.
 */
@WebMvcTest(TransactionController.class)
@Import(SecurityConfig.class)
class TransactionControllerTest {

	private static final String VALID_TOKEN = "valid-access-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TransactionQueryService transactionQueryService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("items 는 유형별로 채워지는 필드가 다르고 나머지는 키를 유지한 채 null 이다 — 명세 예시 그대로")
	void returnsContractedBody() throws Exception {
		givenLoggedIn(42L);
		Instant base = Instant.parse("2026-08-20T05:31:02Z");
		List<TransactionRes> items = List.of(
			new TransactionRes(301L, LedgerType.SELL, KstTime.toResponse(base.plusSeconds(8)), "005930", "삼성전자",
				73_500L, 5L, 367_500L, 11_500L, new BigDecimal("3.23"), null),
			new TransactionRes(300L, LedgerType.WITHDRAWAL, KstTime.toResponse(base.plusSeconds(3)), null, null,
				null, null, 500_000L, null, null, null),
			new TransactionRes(299L, LedgerType.DEPOSIT, KstTime.toResponse(base), null, null,
				null, null, 1_000_000L, null, null, "KAKAOPAY"));
		given(transactionQueryService.list(42L, TransactionFilter.ALL, null, 30))
			.willReturn(new CursorPage<>(items, "eyJpZCI6Mjk4fQ==", true));

		mockMvc.perform(list("/api/v1/transactions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].transactionId").value(301))
			.andExpect(jsonPath("$.items[0].type").value("SELL"))
			.andExpect(jsonPath("$.items[0].occurredAt").value("2026-08-20T14:31:10+09:00"))
			.andExpect(jsonPath("$.items[0].stockCode").value("005930"))
			.andExpect(jsonPath("$.items[0].stockName").value("삼성전자"))
			.andExpect(jsonPath("$.items[0].price").value(73500))
			.andExpect(jsonPath("$.items[0].quantity").value(5))
			.andExpect(jsonPath("$.items[0].amount").value(367500))
			.andExpect(jsonPath("$.items[0].realizedProfit").value(11500))
			.andExpect(jsonPath("$.items[0].realizedProfitRate").value(3.23))
			.andExpect(jsonPath("$.items[0].paymentMethod").value(nullValue()))
			// 출금 — 종목·수단 전부 null 이되 키는 있다. 금액은 양수다.
			.andExpect(jsonPath("$.items[1].type").value("WITHDRAWAL"))
			.andExpect(jsonPath("$.items[1].stockCode").value(nullValue()))
			.andExpect(jsonPath("$.items[1].price").value(nullValue()))
			.andExpect(jsonPath("$.items[1].amount").value(500000))
			.andExpect(jsonPath("$.items[1].realizedProfitRate").value(nullValue()))
			.andExpect(jsonPath("$.items[1].paymentMethod").value(nullValue()))
			// 충전 — 수단만 채워진다.
			.andExpect(jsonPath("$.items[2].type").value("DEPOSIT"))
			.andExpect(jsonPath("$.items[2].amount").value(1000000))
			.andExpect(jsonPath("$.items[2].paymentMethod").value("KAKAOPAY"))
			.andExpect(jsonPath("$.nextCursor").value("eyJpZCI6Mjk4fQ=="))
			.andExpect(jsonPath("$.hasNext").value(true));
	}

	/** 갓 가입한 계정의 정상 초기 상태다 (apiSpec 8.2). 에러가 아니라 빈 목록이고 커서는 null 이다. */
	@Test
	@DisplayName("내역이 없으면 items 는 빈 배열, nextCursor 는 null, hasNext 는 false 다")
	void emptyListIsNotAnError() throws Exception {
		givenLoggedIn(42L);
		given(transactionQueryService.list(anyLong(), any(), any(), anyInt())).willReturn(CursorPage.empty());

		mockMvc.perform(list("/api/v1/transactions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.nextCursor").value(nullValue()))
			.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	@DisplayName("파라미터를 전부 생략하면 type=ALL · cursor 없음 · size=30 으로 서비스를 부른다")
	void appliesDefaults() throws Exception {
		givenLoggedIn(42L);
		given(transactionQueryService.list(anyLong(), any(), any(), anyInt())).willReturn(CursorPage.empty());

		mockMvc.perform(list("/api/v1/transactions")).andExpect(status().isOk());

		verify(transactionQueryService).list(42L, TransactionFilter.ALL, null, 30);
	}

	/** 명세 예시가 {@code ?type=ALL&cursor=&size=30} 이다 — 빈 cursor 는 첫 페이지, 빈 type 은 ALL 이어야 한다. */
	@Test
	@DisplayName("cursor= 처럼 빈 값은 첫 페이지이고 type= 빈 값은 ALL 이다")
	void blankParamsMeanDefaults() throws Exception {
		givenLoggedIn(42L);
		given(transactionQueryService.list(anyLong(), any(), any(), anyInt())).willReturn(CursorPage.empty());

		mockMvc.perform(list("/api/v1/transactions").param("type", "").param("cursor", "").param("size", "30"))
			.andExpect(status().isOk());

		verify(transactionQueryService).list(eq(42L), eq(TransactionFilter.ALL), isNull(), eq(30));
	}

	@Test
	@DisplayName("type · cursor · size 를 주면 그대로 넘긴다")
	void passesParamsThrough() throws Exception {
		givenLoggedIn(42L);
		given(transactionQueryService.list(anyLong(), any(), any(), anyInt())).willReturn(CursorPage.empty());

		mockMvc.perform(list("/api/v1/transactions").param("type", "SELL").param("cursor", "eyJpZCI6Mjk4fQ==")
				.param("size", "10"))
			.andExpect(status().isOk());

		verify(transactionQueryService).list(42L, TransactionFilter.SELL, "eyJpZCI6Mjk4fQ==", 10);
	}

	@Test
	@DisplayName("type 이 열거값 밖이면 400 INVALID_REQUEST 이고 detail 에 type 이 있다 — 서비스는 돌지 않는다")
	void rejectsUnknownType() throws Exception {
		givenLoggedIn(42L);

		mockMvc.perform(list("/api/v1/transactions").param("type", "FOO"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.type").exists());

		verifyNoInteractions(transactionQueryService);
	}

	/** 범위 밖을 조용히 깎아 주지 않는다 ({@code PageSize} 주석). 0 도 101 도 400 이다. */
	@Test
	@DisplayName("size 가 1~100 밖이면 400 INVALID_REQUEST 이고 detail 에 size 가 있다")
	void rejectsSizeOutOfRange() throws Exception {
		givenLoggedIn(42L);

		for (String size : new String[] {"0", "101", "-1"}) {
			mockMvc.perform(list("/api/v1/transactions").param("size", size))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail.size").exists());
		}
		mockMvc.perform(list("/api/v1/transactions").param("size", "abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.detail.size").exists());

		verifyNoInteractions(transactionQueryService);
	}

	/** 커서 해독은 서비스 안({@code CursorCodec})이라 여기서는 그 예외가 400 으로 나가는지만 본다. */
	@Test
	@DisplayName("손상된 cursor 는 400 INVALID_REQUEST 다 — 첫 페이지로 되돌리지 않는다")
	void rejectsCorruptedCursor() throws Exception {
		givenLoggedIn(42L);
		given(transactionQueryService.list(42L, TransactionFilter.ALL, "not-a-cursor", 30))
			.willThrow(new CustomException(GeneralErrorCode.INVALID_REQUEST));

		mockMvc.perform(list("/api/v1/transactions").param("cursor", "not-a-cursor"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("토큰이 없으면 401 이고 서비스는 돌지 않는다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/transactions"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		verifyNoInteractions(transactionQueryService);
	}

	private MockHttpServletRequestBuilder list(String path) {
		return get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
	}

	private void givenLoggedIn(long userId) {
		given(jwtProvider.parseAccessToken(VALID_TOKEN)).willReturn(userId);
	}
}
