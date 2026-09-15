package com.finch.domain.ai.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.deposit.dto.response.MockApproveRes;
import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.domain.deposit.gateway.MockScenario;
import com.finch.domain.deposit.service.DepositService;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.service.LedgerService;
import com.finch.domain.order.dto.request.OrderReq;
import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.service.OrderService;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.domain.withdrawal.service.WithdrawalService;
import com.finch.global.security.InternalTokenFilter;
import com.finch.global.security.JwtProvider;
import com.finch.global.util.MarketClock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code /internal/v1} 을 <b>실제 보안 체인</b>으로 본다 — 별도 {@code SecurityFilterChain} 이 이 경로만 잡고, JWT 체인이 아니라
 * 내부 토큰으로 판정하는지가 이 테스트의 본체다. 슬라이스로는 체인 둘의 순서·매칭을 볼 수 없다.
 * <p>
 * 응답 필드(apiSpec 9.1·9.2)는 실제로 충전·주문한 사용자로 본다. 파생 지표(평가금액 등)가 <b>없는지</b>도 본다 — S0-5 분담.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalApiTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(960_000_000L);
	private static final String TOKEN = "test-only-internal-token";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtProvider jwtProvider;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private DepositService depositService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private PriceCache priceCache;

	@Autowired
	private WithdrawalService withdrawalService;

	@Autowired
	private LedgerService ledgerService;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private MarketClock marketClock;

	@Nested
	@DisplayName("인증 (InternalSecurityConfig)")
	class Auth {

		@Test
		@DisplayName("X-Internal-Token 이 없으면 401 AUTH_INVALID_TOKEN")
		void missingToken() throws Exception {
			mockMvc.perform(get("/internal/v1/portfolio").header("X-User-Id", "1"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
		}

		@Test
		@DisplayName("X-Internal-Token 이 다르면 401")
		void wrongToken() throws Exception {
			mockMvc.perform(get("/internal/v1/portfolio").header(InternalTokenFilter.HEADER, "wrong").header("X-User-Id", "1"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
		}

		/** apiSpec 11.2 — "사용자 JWT 인증은 적용되지 않는다". 사용자 토큰으로는 내부 API 를 열 수 없다. */
		@Test
		@DisplayName("사용자 JWT 만으로는 401 — 이 경로는 JWT 체인이 아니다")
		void jwtAloneIsRejected() throws Exception {
			Long userId = newUser();

			mockMvc.perform(get("/internal/v1/portfolio")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(userId))
					.header("X-User-Id", String.valueOf(userId)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
		}

		@Test
		@DisplayName("사용자가 없으면 404 RESOURCE_NOT_FOUND, 헤더가 없거나 숫자가 아니면 400 INVALID_REQUEST")
		void userHeader() throws Exception {
			mockMvc.perform(internal("/internal/v1/portfolio", "999999999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
			mockMvc.perform(get("/internal/v1/portfolio").header(InternalTokenFilter.HEADER, TOKEN))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail['X-User-Id']").exists());
			mockMvc.perform(internal("/internal/v1/trades", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			mockMvc.perform(internal("/internal/v1/cash-flows", "999999999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
			mockMvc.perform(internal("/internal/v1/cash-flows", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}

		@Test
		@DisplayName("cash-flows 도 X-Internal-Token 이 없으면 401")
		void cashFlowsRequireToken() throws Exception {
			mockMvc.perform(get("/internal/v1/cash-flows").header("X-User-Id", "1"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
		}
	}

	@Nested
	@DisplayName("응답 (apiSpec 9.1·9.2)")
	class Body {

		@Test
		@DisplayName("portfolio — cashBalance·asOf·holdings[stockCode,stockName,quantity,avgBuyPrice,currentPrice], 파생 지표 없음")
		void portfolio() throws Exception {
			Long userId = tradedUser();

			mockMvc.perform(internal("/internal/v1/portfolio", " " + userId + " "))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cashBalance").value(300000))
				.andExpect(jsonPath("$.asOf").isString())
				.andExpect(jsonPath("$.holdings.length()").value(1))
				.andExpect(jsonPath("$.holdings[0].stockCode").value("005930"))
				.andExpect(jsonPath("$.holdings[0].stockName").value("삼성전자"))
				.andExpect(jsonPath("$.holdings[0].quantity").value(10))
				.andExpect(jsonPath("$.holdings[0].avgBuyPrice").value(70000))
				.andExpect(jsonPath("$.holdings[0].currentPrice").value(70000))
				.andExpect(jsonPath("$.holdings[0].evaluationAmount").doesNotExist())
				.andExpect(jsonPath("$.evaluationAmount").doesNotExist())
				.andExpect(jsonPath("$.totalAsset").doesNotExist());
		}

		@Test
		@DisplayName("trades — 키가 trades 이고 tradeId·stockCode·side·price·quantity·executedAt(KST)·cashBalanceAfter, 최신순, 커서 페이징 기본 100")
		void trades() throws Exception {
			Long userId = tradedUser();
			priceCache.put("005930", new PriceEntry(72_000L, 72_000L, Instant.now()));
			orderService.place(userId, new OrderReq("005930", OrderSide.SELL, 3L));

			mockMvc.perform(internal("/internal/v1/trades", String.valueOf(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.trades.length()").value(2))
				.andExpect(jsonPath("$.trades[0].side").value("SELL"))
				.andExpect(jsonPath("$.trades[0].price").value(72000))
				.andExpect(jsonPath("$.trades[0].quantity").value(3))
				.andExpect(jsonPath("$.trades[0].stockCode").value("005930"))
				.andExpect(jsonPath("$.trades[0].tradeId").isNumber())
				.andExpect(jsonPath("$.trades[0].executedAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
				// 체결 직후 예수금 — 30만 + 7.2만 × 3 = 51.6만, 매수 직후는 100만 − 70만 = 30만.
				.andExpect(jsonPath("$.trades[0].cashBalanceAfter").value(516000))
				.andExpect(jsonPath("$.trades[1].side").value("BUY"))
				.andExpect(jsonPath("$.trades[1].cashBalanceAfter").value(300000))
				.andExpect(jsonPath("$.trades[0].fee").doesNotExist())
				.andExpect(jsonPath("$.trades[0].realizedProfit").doesNotExist())
				.andExpect(jsonPath("$.items").doesNotExist())
				.andExpect(jsonPath("$.nextCursor").value(nullValue()))
				.andExpect(jsonPath("$.hasNext").value(false));

			// size=1 → 한 건 + 커서, 커서로 다음 페이지 → 나머지 한 건.
			String cursor = mockMvc.perform(internal("/internal/v1/trades", String.valueOf(userId)).param("size", "1"))
				.andExpect(jsonPath("$.trades.length()").value(1))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andReturn().getResponse().getContentAsString()
				.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
			mockMvc.perform(internal("/internal/v1/trades", String.valueOf(userId)).param("size", "1").param("cursor", cursor))
				.andExpect(jsonPath("$.trades.length()").value(1))
				.andExpect(jsonPath("$.trades[0].side").value("BUY"))
				.andExpect(jsonPath("$.hasNext").value(false));
		}

		@Test
		@DisplayName("거래가 없으면 빈 trades 이고 에러가 아니다")
		void emptyTrades() throws Exception {
			Long userId = newUser();

			mockMvc.perform(internal("/internal/v1/trades", String.valueOf(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.trades.length()").value(0))
				.andExpect(jsonPath("$.hasNext").value(false));
		}
	}

	@Nested
	@DisplayName("입출금 이력 (apiSpec 9.3)")
	class CashFlows {

		@Test
		@DisplayName("충전·출금만 나오고 매수·매도는 없다 — amount 는 부호 포함, cashBalanceAfter 는 원장 값, 최신순, KST")
		void externalFlowsOnly() throws Exception {
			Long userId = tradedUser();
			priceCache.put("005930", new PriceEntry(72_000L, 72_000L, Instant.now()));
			orderService.place(userId, new OrderReq("005930", OrderSide.SELL, 3L));
			withdrawalService.withdraw(userId, 100_000L);

			mockMvc.perform(internal("/internal/v1/cash-flows", String.valueOf(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cashFlows.length()").value(2))
				.andExpect(jsonPath("$.cashFlows[0].type").value("WITHDRAWAL"))
				.andExpect(jsonPath("$.cashFlows[0].amount").value(-100000))
				.andExpect(jsonPath("$.cashFlows[0].cashBalanceAfter").value(416000))
				.andExpect(jsonPath("$.cashFlows[0].entryId").isNumber())
				.andExpect(jsonPath("$.cashFlows[0].occurredAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
				.andExpect(jsonPath("$.cashFlows[1].type").value("DEPOSIT"))
				.andExpect(jsonPath("$.cashFlows[1].amount").value(1000000))
				.andExpect(jsonPath("$.cashFlows[1].cashBalanceAfter").value(1000000))
				.andExpect(jsonPath("$.items").doesNotExist())
				.andExpect(jsonPath("$.nextCursor").value(nullValue()))
				.andExpect(jsonPath("$.hasNext").value(false));
		}

		/** 지급액 정책이 지금 0 이라 서비스로는 만들 수 없다 — 정책이 되살아났을 때의 원장 행을 직접 남긴다 (TransactionQueryServiceTest 와 같다). */
		@Test
		@DisplayName("INITIAL_GRANT 도 외부 흐름으로 나온다 — 빠지면 AI 가 기초 자금을 다시 역산한다")
		void initialGrantIsIncluded() throws Exception {
			Long userId = newUser();
			Long accountId = accountService.getBalance(userId).accountId();
			transactionTemplate.executeWithoutResult(status ->
				ledgerService.record(accountId, LedgerType.INITIAL_GRANT, 50_000L, 50_000L, Instant.now()));

			mockMvc.perform(internal("/internal/v1/cash-flows", String.valueOf(userId)))
				.andExpect(jsonPath("$.cashFlows.length()").value(1))
				.andExpect(jsonPath("$.cashFlows[0].type").value("INITIAL_GRANT"))
				.andExpect(jsonPath("$.cashFlows[0].amount").value(50000));
		}

		@Test
		@DisplayName("다른 사용자의 원장은 섞이지 않는다")
		void isolatedPerUser() throws Exception {
			Long userId = depositedUser(200_000L);
			depositedUser(700_000L);

			mockMvc.perform(internal("/internal/v1/cash-flows", String.valueOf(userId)))
				.andExpect(jsonPath("$.cashFlows.length()").value(1))
				.andExpect(jsonPath("$.cashFlows[0].amount").value(200000));
		}

		@Test
		@DisplayName("size=1 이면 한 건 + 커서, 커서로 다음 페이지. 흐름이 없으면 빈 목록이고 에러가 아니다")
		void pagingAndEmpty() throws Exception {
			Long userId = depositedUser(200_000L);
			withdrawalService.withdraw(userId, 50_000L);

			String cursor = mockMvc.perform(internal("/internal/v1/cash-flows", String.valueOf(userId)).param("size", "1"))
				.andExpect(jsonPath("$.cashFlows.length()").value(1))
				.andExpect(jsonPath("$.cashFlows[0].type").value("WITHDRAWAL"))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andReturn().getResponse().getContentAsString()
				.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
			mockMvc.perform(internal("/internal/v1/cash-flows", String.valueOf(userId)).param("size", "1")
					.param("cursor", cursor))
				.andExpect(jsonPath("$.cashFlows.length()").value(1))
				.andExpect(jsonPath("$.cashFlows[0].type").value("DEPOSIT"))
				.andExpect(jsonPath("$.hasNext").value(false));

			mockMvc.perform(internal("/internal/v1/cash-flows", String.valueOf(newUser())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cashFlows.length()").value(0))
				.andExpect(jsonPath("$.hasNext").value(false));
		}
	}

	// ---- helpers ----

	private static MockHttpServletRequestBuilder internal(String path, String userId) {
		return get(path).header(InternalTokenFilter.HEADER, TOKEN).header("X-User-Id", userId);
	}

	private Long newUser() {
		User user = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg"));
		accountService.ensureAccount(user.getId());
		return user.getId();
	}

	private Long depositedUser(long amount) {
		Long userId = newUser();
		Long paymentId = depositService.ready(userId, PaymentMethod.TRANSFER, amount).paymentId();
		MockApproveRes approved = depositService.mockApprove(userId, paymentId, MockScenario.SUCCESS);
		depositService.confirm(userId, approved.paymentId(), approved.paymentKey(), amount);
		return userId;
	}

	/** 100만 충전 → 삼성전자 7만 원 10주 매수. 예수금 30만, 보유 10주. */
	private Long tradedUser() {
		Long userId = depositedUser(1_000_000L);
		BDDMockito.given(marketClock.isOpen()).willReturn(true);
		priceCache.put("005930", new PriceEntry(70_000L, 70_000L, Instant.now()));
		orderService.place(userId, new OrderReq("005930", OrderSide.BUY, 10L));
		return userId;
	}
}
