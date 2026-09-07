package com.finch.domain.withdrawal.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.finch.domain.withdrawal.repository.WithdrawalRepository;
import com.finch.global.idempotency.IdempotencyFilter;
import com.finch.global.security.JwtProvider;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 출금의 재전송 방어가 <b>실제 필터 체인</b>에서 성립하는지 본다 (apiSpec 1.4·4.5 판정 1).
 * <p>
 * {@code IdempotencyFilterTest} 가 필터 자체를 더미 엔드포인트로 검증했으므로 여기서는 갈래를 되풀이하지 않는다.
 * 보는 것은 하나 — <b>{@code /api/v1/withdrawals} 가 배포 설정({@code finch.idempotency.paths})으로 실제로 필터에
 * 걸리고</b>, 그 결과 같은 키의 두 번째 요청이 원장·withdrawal 행을 더하지 않는다는 것. 경로 목록에서 빠지면
 * 컨트롤러는 그대로 돌고 예수금이 두 번 빠진다 — 그것이 이 테스트가 잡는 회귀다.
 * <p>
 * 키는 요청마다 UUID 로 새로 만든다. 장부가 사용자별({@code idem:{userId}:{key}})이고 사용자도 테스트마다 새로
 * 만들므로 Redis 를 비우지 않아도 서로 섞이지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawalIdempotencyTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(930_000_000L);
	private static final long FUNDED = 1_000_000L;
	private static final String BODY = "{\"amount\":400000}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtProvider jwtProvider;

	@Autowired
	private DepositService depositService;

	@Autowired
	private WithdrawalRepository withdrawalRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	/** 판정 1 (apiSpec 4.5). 헤더 검사가 본문 검증보다 앞이라 본문이 멀쩡해도 여기서 끝난다. */
	@Test
	@DisplayName("Idempotency-Key 가 없으면 400 IDEMPOTENCY_KEY_REQUIRED 이고 예수금은 그대로다")
	void missingKeyIsRejected() throws Exception {
		Long userId = fundedUser();

		mockMvc.perform(post("/api/v1/withdrawals")
				.header(HttpHeaders.AUTHORIZATION, bearer(userId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

		assertThat(account(userId).getCashBalance()).isEqualTo(FUNDED);
		assertThat(withdrawalRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).isEmpty();
	}

	/** 사용자가 출금 버튼을 두 번 눌렀거나 네트워크가 재시도한 모습이다. 돈은 한 번만 빠지고 두 번째는 최초 본문이다. */
	@Test
	@DisplayName("같은 키 2회 → 201 둘 다, 같은 본문, withdrawal 1행, 예수금은 한 번만 준다")
	void sameKeyIsReplayed() throws Exception {
		Long userId = fundedUser();
		String key = UUID.randomUUID().toString();

		String first = mockMvc.perform(withdraw(userId, key, BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.cashBalanceAfter").value(600000))
			.andReturn().getResponse().getContentAsString();
		String second = mockMvc.perform(withdraw(userId, key, BODY))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(account(userId).getCashBalance()).isEqualTo(600_000L);
		assertThat(withdrawalRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).hasSize(1);
	}

	@Test
	@DisplayName("같은 키·다른 금액은 409 IDEMPOTENCY_CONFLICT 이고 두 번째 금액은 빠지지 않는다")
	void sameKeyDifferentBodyConflicts() throws Exception {
		Long userId = fundedUser();
		String key = UUID.randomUUID().toString();
		mockMvc.perform(withdraw(userId, key, BODY)).andExpect(status().isCreated());

		mockMvc.perform(withdraw(userId, key, "{\"amount\":1}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

		assertThat(account(userId).getCashBalance()).isEqualTo(600_000L);
		assertThat(withdrawalRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).hasSize(1);
	}

	/**
	 * 4xx 도 장부에 남는다 ({@code IdempotencyFilter.execute}). 같은 키로 다시 보내면 컨트롤러를 거치지 않고 같은 409 다 —
	 * "동일 상태 코드로 최초 결과 반환"(apiSpec 1.4). 그 사이 충전으로 잔고가 늘었어도 <b>같은 키로는</b> 성공하지 않는다.
	 * 새 시도는 새 키다.
	 */
	@Test
	@DisplayName("부족 판정 409 도 같은 키로는 그대로 재생된다 — 잔고가 늘어도 새 키여야 다시 시도된다")
	void rejectionIsReplayedToo() throws Exception {
		Long userId = fundedUser();
		String key = UUID.randomUUID().toString();
		String tooMuch = "{\"amount\":1500000}";
		mockMvc.perform(withdraw(userId, key, tooMuch))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_INSUFFICIENT_CASH"))
			.andExpect(jsonPath("$.detail.availableAmount").value(1000000));

		fund(userId, 1_000_000L);
		mockMvc.perform(withdraw(userId, key, tooMuch))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_INSUFFICIENT_CASH"))
			.andExpect(jsonPath("$.detail.availableAmount").value(1000000));
		mockMvc.perform(withdraw(userId, UUID.randomUUID().toString(), tooMuch))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.cashBalanceAfter").value(500000));
	}

	private RequestBuilder withdraw(Long userId, String key, String body) {
		return post("/api/v1/withdrawals")
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
		fund(user.getId(), FUNDED);
		return user.getId();
	}

	private void fund(Long userId, long amount) {
		Long paymentId = depositService.ready(userId, PaymentMethod.TRANSFER, amount).paymentId();
		MockApproveRes approved = depositService.mockApprove(userId, paymentId, MockScenario.SUCCESS);
		depositService.confirm(userId, approved.paymentId(), approved.paymentKey(), amount);
	}

	private Account account(Long userId) {
		return accountRepository.findByUserId(userId).orElseThrow();
	}
}
