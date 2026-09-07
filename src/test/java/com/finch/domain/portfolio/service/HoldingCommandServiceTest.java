package com.finch.domain.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.dto.response.AccountBalanceRes;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.portfolio.entity.Holding;
import com.finch.domain.portfolio.repository.HoldingRepository;
import com.finch.domain.portfolio.service.HoldingCommandService.SellResult;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 체결이 보유를 어떻게 바꾸는지. 여기서 틀리면 원장은 맞는데 잔고가 틀리는 상태가 되고, 그것은 화면에서만 보인다.
 * <p>
 * S9(주문)가 아직 없어 호출자는 이 테스트다. 그래서 <b>트랜잭션을 직접 연다</b> — {@code MANDATORY} 라 밖에서 부르면 실패하는데,
 * 그 실패 자체도 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldingCommandServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(1_000_000_000L);
	private static final String SAMSUNG = "005930";

	@Autowired
	private HoldingCommandService holdingCommandService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Nested
	@DisplayName("매수")
	class Buy {

		@Test
		@DisplayName("처음 사는 종목은 첫 체결가가 평단이다")
		void opensHolding() {
			Long accountId = newAccountId();

			long avg = inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 10, 70_000));

			assertThat(avg).isEqualTo(70_000);
			Holding holding = findHolding(accountId);
			assertThat(holding.getQuantity()).isEqualTo(10);
			assertThat(holding.getAvgBuyPrice()).isEqualTo(70_000);
		}

		@Test
		@DisplayName("추가 매수는 가중평균이다 — (70,000×10 + 80,000×10) / 20 = 75,000")
		void averagesWeighted() {
			Long accountId = newAccountId();

			inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 10, 70_000));
			long avg = inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 10, 80_000));

			assertThat(avg).isEqualTo(75_000);
			assertThat(findHolding(accountId).getQuantity()).isEqualTo(20);
		}

		@Test
		@DisplayName("나누어떨어지지 않으면 버린다 — (10,000×1 + 10,001×1) / 2 = 10,000.5 → 10,000")
		void floorsAverage() {
			Long accountId = newAccountId();

			inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 1, 10_000));
			long avg = inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 1, 10_001));

			// 반올림하면 10,001 이 된다. 평단이 원 단위 정수라 한쪽으로 몰아야 하고, 버림이면 평단이 실제 원가보다
			// 작아져 수익률이 실제보다 커 보인다 — 어느 쪽이든 어긋나므로 방향을 고정해 두는 것이 요점이다.
			assertThat(avg).isEqualTo(10_000);
		}
	}

	@Nested
	@DisplayName("매도")
	class Sell {

		@Test
		@DisplayName("실현손익은 (체결가 − 평단) × 수량이고 평단은 그대로다")
		void realizesProfit() {
			Long accountId = newAccountId();
			inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 10, 70_000));

			SellResult result = inTransaction(() -> holdingCommandService.applySell(accountId, SAMSUNG, 4, 80_000));

			assertThat(result.avgBuyPriceAtSell()).isEqualTo(70_000);
			assertThat(result.realizedProfit()).isEqualTo(40_000);
			Holding holding = findHolding(accountId);
			assertThat(holding.getQuantity()).isEqualTo(6);
			assertThat(holding.getAvgBuyPrice()).isEqualTo(70_000);
		}

		@Test
		@DisplayName("손실이면 실현손익이 음수다 — 0 으로 자르지 않는다")
		void realizesLoss() {
			Long accountId = newAccountId();
			inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 10, 70_000));

			SellResult result = inTransaction(() -> holdingCommandService.applySell(accountId, SAMSUNG, 10, 60_000));

			assertThat(result.realizedProfit()).isEqualTo(-100_000);
		}

		@Test
		@DisplayName("전량 매도해도 행은 남는다 — 수량·평단만 0 이다")
		void keepsZeroedRow() {
			Long accountId = newAccountId();
			inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 10, 70_000));

			inTransaction(() -> holdingCommandService.applySell(accountId, SAMSUNG, 10, 80_000));

			Holding holding = findHolding(accountId);
			assertThat(holding.getQuantity()).isZero();
			assertThat(holding.getAvgBuyPrice()).isZero();
		}

		@Test
		@DisplayName("전량 매도 뒤 재매수하면 새 체결가가 평단이다 — 옛 평단이 섞이지 않는다")
		void rebuysCleanly() {
			Long accountId = newAccountId();
			inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 10, 70_000));
			inTransaction(() -> holdingCommandService.applySell(accountId, SAMSUNG, 10, 80_000));

			long avg = inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 5, 90_000));

			assertThat(avg).isEqualTo(90_000);
			assertThat(findHolding(accountId).getQuantity()).isEqualTo(5);
			// 재매수가 INSERT 였다면 uq_holding_account_stock 에 걸렸을 것이다. 행이 하나뿐인 것이 그 증거다.
			assertThat(holdingRepository.findAll().stream()
				.filter(h -> h.getAccountId().equals(accountId))
				.count()).isEqualTo(1);
		}

		@Test
		@DisplayName("보유 수량보다 많이 팔면 IllegalStateException — 사용자 에러가 아니라 주문의 버그다")
		void rejectsOversell() {
			Long accountId = newAccountId();
			inTransaction(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 10, 70_000));

			assertThatThrownBy(() -> inTransaction(() -> holdingCommandService.applySell(accountId, SAMSUNG, 11, 80_000)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("보유 수량보다 많이");
		}

		@Test
		@DisplayName("보유하지 않은 종목을 팔면 IllegalStateException")
		void rejectsUnheldSell() {
			Long accountId = newAccountId();

			assertThatThrownBy(() -> inTransaction(() -> holdingCommandService.applySell(accountId, SAMSUNG, 1, 80_000)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("보유하지 않은");
		}
	}

	@Nested
	@DisplayName("트랜잭션")
	class Transactions {

		@Test
		@DisplayName("트랜잭션 밖에서 부르면 실패한다 — 보유만 늘어난 채로 남는 경로를 막는다")
		void requiresTransaction() {
			Long accountId = newAccountId();

			assertThatThrownBy(() -> holdingCommandService.applyBuy(accountId, SAMSUNG, 10, 70_000))
				.isInstanceOf(IllegalTransactionStateException.class);
			assertThatThrownBy(() -> holdingCommandService.applySell(accountId, SAMSUNG, 10, 70_000))
				.isInstanceOf(IllegalTransactionStateException.class);
		}
	}

	// ---- helpers ----

	private <T> T inTransaction(Supplier<T> action) {
		return transactionTemplate.execute(status -> action.get());
	}

	private Holding findHolding(Long accountId) {
		return holdingRepository.findByAccountIdAndStockCode(accountId, SAMSUNG).orElseThrow();
	}

	private Long newAccountId() {
		Long userId = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg"))
			.getId();
		accountService.ensureAccount(userId);
		AccountBalanceRes balance = accountService.getBalance(userId);
		return balance.accountId();
	}
}
