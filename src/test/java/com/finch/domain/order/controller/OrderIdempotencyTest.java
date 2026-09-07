package com.finch.domain.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.entity.Account;
import com.finch.domain.account.repository.AccountRepository;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.deposit.dto.response.MockApproveRes;
import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.domain.deposit.gateway.MockScenario;
import com.finch.domain.deposit.service.DepositService;
import com.finch.domain.order.repository.TradeRepository;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.global.idempotency.IdempotencyFilter;
import com.finch.global.security.JwtProvider;
import com.finch.global.util.MarketClock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 주문의 재전송 방어가 <b>실제 필터 체인</b>에서 성립하는지 본다 (apiSpec 1.4·11.1 — 멱등성은 본문 검증보다 앞이다).
 * <p>
 * 보는 것은 하나 — {@code /api/v1/orders} 가 배포 설정({@code finch.idempotency.paths})으로 실제로 필터에 걸리고, 그 결과 같은 키의
 * 두 번째 요청이 원장·trade·holding 을 더하지 않는다는 것. 경로가 목록에서 빠지면 더블클릭이 두 번 체결된다 — 그것이 이 테스트가
 * 잡는 회귀다. {@code WithdrawalIdempotencyTest} 와 같은 구조다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderIdempotencyTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(950_000_000L);
	private static final long FUNDED = 1_000_000L;
	private static final String BODY = "{\"stockCode\":\"005930\",\"side\":\"BUY\",\"quantity\":10}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtProvider jwtProvider;

	@Autowired
	private DepositService depositService;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PriceCache priceCache;

	@MockitoBean
	private MarketClock marketClock;

	@BeforeEach
	void marketIsOpenAndPriced() {
		given(marketClock.isOpen()).willReturn(true);
		priceCache.put("005930", new PriceEntry(70_000L, 70_000L, Instant.now()));
	}

	/** 판정의 첫 칸 (apiSpec 11.2). 헤더 검사가 본문 검증보다 앞이라 본문이 멀쩡해도 여기서 끝난다. */
	@Test
	@DisplayName("Idempotency-Key 가 없으면 400 IDEMPOTENCY_KEY_REQUIRED 이고 아무것도 체결되지 않는다")
	void missingKeyIsRejected() throws Exception {
		Long userId = fundedUser();

		mockMvc.perform(post("/api/v1/orders")
				.header(HttpHeaders.AUTHORIZATION, bearer(userId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

		assertThat(account(userId).getCashBalance()).isEqualTo(FUNDED);
		assertThat(tradeRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).isEmpty();
	}

	/** 사용자가 주문 버튼을 두 번 눌렀거나 네트워크가 재시도한 모습이다. 체결은 한 번이고 두 번째는 최초 본문이다. */
	@Test
	@DisplayName("같은 키 2회 → 201 둘 다, 같은 본문(같은 orderId), trade 1행, 예수금은 한 번만 준다")
	void sameKeyIsReplayed() throws Exception {
		Long userId = fundedUser();
		String key = UUID.randomUUID().toString();

		String first = mockMvc.perform(order(userId, key, BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.cashBalanceAfter").value(300000))
			.andReturn().getResponse().getContentAsString();
		String second = mockMvc.perform(order(userId, key, BODY))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(account(userId).getCashBalance()).isEqualTo(300_000L);
		assertThat(tradeRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).hasSize(1);
	}

	@Test
	@DisplayName("같은 키·다른 본문은 409 IDEMPOTENCY_CONFLICT 이고 두 번째는 체결되지 않는다")
	void sameKeyDifferentBodyConflicts() throws Exception {
		Long userId = fundedUser();
		String key = UUID.randomUUID().toString();
		mockMvc.perform(order(userId, key, BODY)).andExpect(status().isCreated());

		mockMvc.perform(order(userId, key, "{\"stockCode\":\"005930\",\"side\":\"BUY\",\"quantity\":1}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

		assertThat(tradeRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).hasSize(1);
	}

	/** 새 클릭은 새 키다 — 같은 본문이라도 키가 다르면 두 번째 주문이다 (apiSpec 1.4). */
	@Test
	@DisplayName("다른 키·같은 본문은 두 번 체결된다")
	void differentKeyIsNewOrder() throws Exception {
		Long userId = fundedUser();

		mockMvc.perform(order(userId, UUID.randomUUID().toString(), "{\"stockCode\":\"005930\",\"side\":\"BUY\",\"quantity\":5}"))
			.andExpect(status().isCreated());
		mockMvc.perform(order(userId, UUID.randomUUID().toString(), "{\"stockCode\":\"005930\",\"side\":\"BUY\",\"quantity\":5}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.cashBalanceAfter").value(300000));

		assertThat(tradeRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).hasSize(2);
	}

	private RequestBuilder order(Long userId, String key, String body) {
		return post("/api/v1/orders")
			.header(HttpHeaders.AUTHORIZATION, bearer(userId))
			.header(IdempotencyFilter.HEADER, key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private String bearer(Long userId) {
		return "Bearer " + jwtProvider.createAccessToken(userId);
	}

	private Long fundedUser() {
		User user = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg"));
		accountService.ensureAccount(user.getId());
		Long paymentId = depositService.ready(user.getId(), PaymentMethod.TRANSFER, FUNDED).paymentId();
		MockApproveRes approved = depositService.mockApprove(user.getId(), paymentId, MockScenario.SUCCESS);
		depositService.confirm(user.getId(), approved.paymentId(), approved.paymentKey(), FUNDED);
		return user.getId();
	}

	private Account account(Long userId) {
		return accountRepository.findByUserId(userId).orElseThrow();
	}
}
