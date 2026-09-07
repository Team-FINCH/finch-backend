package com.finch.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

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
import com.finch.domain.ledger.entity.LedgerEntry;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.LedgerEntryRepository;
import com.finch.domain.order.dto.request.OrderReq;
import com.finch.domain.order.dto.response.OrderAvailableRes;
import com.finch.domain.order.dto.response.OrderRes;
import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.entity.Trade;
import com.finch.domain.order.exception.OrderErrorCode;
import com.finch.domain.order.repository.TradeRepository;
import com.finch.domain.portfolio.entity.Holding;
import com.finch.domain.portfolio.repository.HoldingRepository;
import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.domain.stock.entity.Market;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.stock.repository.StockRepository;
import com.finch.global.apiPayload.code.BaseErrorCode;
import com.finch.global.exception.CustomException;
import com.finch.global.util.MarketClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문이 실제 DB·Redis 에서 무엇을 남기는지 본다. 목으로는 볼 수 없는 것들이 대상이다 — FOR UPDATE 의 줄 세우기, 불변식 1·3·6,
 * 원장·체결·보유·계좌 <b>네 곳</b>이 한 트랜잭션에서 함께 바뀌거나 함께 남지 않는 것.
 * <p>
 * <b>{@code MarketClock} 만 목이다.</b> 실제 시계로 두면 테스트가 도는 시각(주말·장외)에 따라 결과가 달라진다. 기본은 "장중"
 * 이고, 장외 판정을 보는 테스트가 false 로 바꾼다. 시세는 진짜 Redis 캐시에 직접 넣는다 — Fake 공급자는 테스트 설정에서
 * 꺼져 있어({@code auto-start: false}) 값이 흔들리지 않는다.
 * <p>
 * 예수금은 충전(S3)으로 만든다 — 초기 지급이 0 이라 잔고를 만드는 정상 경로가 충전뿐이다 ({@code WithdrawalServiceTest} 와 같다).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(940_000_000L);
	private static final String SAMSUNG = "005930";
	private static final long FUNDED = 1_000_000L;

	@Autowired
	private OrderService orderService;

	@Autowired
	private DepositService depositService;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private PriceCache priceCache;

	@Autowired
	private PriceProperties priceProperties;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private MarketClock marketClock;

	@BeforeEach
	void marketIsOpen() {
		given(marketClock.isOpen()).willReturn(true);
	}

	@Nested
	@DisplayName("매수 체결")
	class Buy {

		@Test
		@DisplayName("원장(BUY, cash_delta 음수)·trade·holding·account 네 곳이 한 트랜잭션에서 갱신된다")
		void reflectsBuy() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);

			OrderRes res = orderService.place(userId, buy(SAMSUNG, 10));

			assertThat(res.stockCode()).isEqualTo(SAMSUNG);
			assertThat(res.stockName()).isEqualTo("삼성전자");
			assertThat(res.side()).isEqualTo(OrderSide.BUY);
			assertThat(res.quantity()).isEqualTo(10);
			assertThat(res.executedPrice()).isEqualTo(70_000);
			assertThat(res.executedAmount()).isEqualTo(700_000);
			assertThat(res.cashBalanceAfter()).isEqualTo(300_000);
			assertThat(res.realizedProfit()).isNull();
			// apiSpec 1.1 — 시각은 KST 오프셋을 포함한다.
			assertThat(res.executedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);

			Account account = account(userId);
			assertThat(account.getCashBalance()).isEqualTo(300_000);
			// 매매는 누적 충전액을 건드리지 않는다.
			assertThat(account.getTotalDepositedAmount()).isEqualTo(FUNDED);

			LedgerEntry entry = ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId()).getFirst();
			assertThat(entry.getType()).isEqualTo(LedgerType.BUY);
			assertThat(entry.getCashDelta()).isEqualTo(-700_000);
			assertThat(entry.getCashBalanceAfter()).isEqualTo(300_000);

			Trade trade = tradeRepository.findById(res.orderId()).orElseThrow();
			assertThat(trade.getLedgerEntryId()).isEqualTo(entry.getId());
			assertThat(trade.getAccountId()).isEqualTo(account.getId());
			assertThat(trade.getAvgBuyPrice()).isNull();
			assertThat(trade.getRealizedProfit()).isNull();
			assertThat(trade.getExecutedAt()).isEqualTo(entry.getOccurredAt());
			assertThat(res.executedAt().toInstant()).isEqualTo(entry.getOccurredAt());

			Holding holding = holding(account.getId(), SAMSUNG);
			assertThat(holding.getQuantity()).isEqualTo(10);
			assertThat(holding.getAvgBuyPrice()).isEqualTo(70_000);
		}

		@Test
		@DisplayName("예수금과 정확히 같은 금액은 성공하고 cash_balance = 0 이다 — 전액 매수")
		void exactCashIsAllowed() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 100_000);

			OrderRes res = orderService.place(userId, buy(SAMSUNG, 10));

			assertThat(res.cashBalanceAfter()).isZero();
			assertThat(account(userId).getCashBalance()).isZero();
		}

		@Test
		@DisplayName("추가 매수는 평단이 가중평균이다 — 70,000×10 + 80,000×10 → 75,000")
		void averagesOnRebuy() {
			Long userId = fundedUser(2_000_000L);
			price(SAMSUNG, 70_000);
			orderService.place(userId, buy(SAMSUNG, 10));
			price(SAMSUNG, 80_000);

			orderService.place(userId, buy(SAMSUNG, 10));

			Holding holding = holding(account(userId).getId(), SAMSUNG);
			assertThat(holding.getQuantity()).isEqualTo(20);
			assertThat(holding.getAvgBuyPrice()).isEqualTo(75_000);
		}
	}

	@Nested
	@DisplayName("매도 체결")
	class Sell {

		@Test
		@DisplayName("실현손익 = (체결가 − 평단) × 수량, trade 는 매도 시점 평단을 스냅샷으로 갖고 평단은 그대로다")
		void realizesProfit() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);
			orderService.place(userId, buy(SAMSUNG, 10));
			price(SAMSUNG, 80_000);

			OrderRes res = orderService.place(userId, sell(SAMSUNG, 4));

			assertThat(res.side()).isEqualTo(OrderSide.SELL);
			assertThat(res.executedAmount()).isEqualTo(320_000);
			assertThat(res.realizedProfit()).isEqualTo(40_000);
			assertThat(res.cashBalanceAfter()).isEqualTo(620_000);

			Account account = account(userId);
			assertThat(account.getCashBalance()).isEqualTo(620_000);
			LedgerEntry entry = ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId()).getFirst();
			assertThat(entry.getType()).isEqualTo(LedgerType.SELL);
			assertThat(entry.getCashDelta()).isEqualTo(320_000);

			Trade trade = tradeRepository.findById(res.orderId()).orElseThrow();
			assertThat(trade.getAvgBuyPrice()).isEqualTo(70_000);
			assertThat(trade.getRealizedProfit()).isEqualTo(40_000);

			Holding holding = holding(account.getId(), SAMSUNG);
			assertThat(holding.getQuantity()).isEqualTo(6);
			assertThat(holding.getAvgBuyPrice()).isEqualTo(70_000);
		}

		@Test
		@DisplayName("손실이면 실현손익이 음수다")
		void realizesLoss() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);
			orderService.place(userId, buy(SAMSUNG, 10));
			price(SAMSUNG, 60_000);

			OrderRes res = orderService.place(userId, sell(SAMSUNG, 10));

			assertThat(res.realizedProfit()).isEqualTo(-100_000);
		}

		@Test
		@DisplayName("전량 매도 — 보유 행은 지우지 않고 quantity·avg_buy_price 가 0 이며 available 의 holdingQuantity 도 0 이다")
		void fullSellZeroesHolding() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);
			orderService.place(userId, buy(SAMSUNG, 10));

			OrderRes res = orderService.place(userId, sell(SAMSUNG, 10));

			assertThat(res.cashBalanceAfter()).isEqualTo(FUNDED);
			Holding holding = holding(account(userId).getId(), SAMSUNG);
			assertThat(holding.getQuantity()).isZero();
			assertThat(holding.getAvgBuyPrice()).isZero();
			assertThat(orderService.available(userId, SAMSUNG, OrderSide.SELL).holdingQuantity()).isZero();
			// 스냅샷은 남는다 — 그때의 평단으로 수익률을 재현할 수 있어야 한다.
			assertThat(tradeRepository.findById(res.orderId()).orElseThrow().getAvgBuyPrice()).isEqualTo(70_000);
		}
	}

	/** erd.md §4 — 원장과 스냅샷이 갈라지면 여기서 잡힌다. 매수·매도를 섞은 뒤 대조한다. */
	@Test
	@DisplayName("불변식 1·3·6 — 잔고 = 원장 합, 보유 = 체결 합, BUY·SELL 원장 1행 = trade 1행")
	void satisfiesInvariants() {
		Long userId = fundedUser(2_000_000L);
		price(SAMSUNG, 70_000);
		orderService.place(userId, buy(SAMSUNG, 10));
		price(SAMSUNG, 75_000);
		orderService.place(userId, sell(SAMSUNG, 3));
		price(SAMSUNG, 72_000);
		orderService.place(userId, buy(SAMSUNG, 5));

		Account account = account(userId);
		List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId());
		List<Trade> trades = tradeRepository.findByAccountIdOrderByIdDesc(account.getId());

		// 1
		assertThat(account.getCashBalance())
			.isEqualTo(entries.stream().mapToLong(LedgerEntry::getCashDelta).sum())
			.isEqualTo(2_000_000L - 700_000L + 225_000L - 360_000L);
		// 3
		long netQuantity = trades.stream()
			.mapToLong(t -> t.getSide().isBuy() ? t.getQuantity() : -t.getQuantity()).sum();
		assertThat(holding(account.getId(), SAMSUNG).getQuantity()).isEqualTo(netQuantity).isEqualTo(12);
		// 6
		List<LedgerEntry> tradeEntries = entries.stream()
			.filter(e -> e.getType() == LedgerType.BUY || e.getType() == LedgerType.SELL).toList();
		assertThat(tradeEntries).hasSize(3);
		assertThat(trades).extracting(Trade::getLedgerEntryId)
			.containsExactlyInAnyOrderElementsOf(tradeEntries.stream().map(LedgerEntry::getId).toList());
	}

	@Nested
	@DisplayName("판정 순서 (apiSpec 11.2)")
	class Rejection {

		@Test
		@DisplayName("수량 0 이하는 종목을 보기 전에 ORDER_QUANTITY_INVALID — 없는 종목이어도 이 코드다")
		void quantityBeforeStock() {
			Long userId = fundedUser(FUNDED);

			assertRejected(() -> orderService.place(userId, buy("999999", 0)), OrderErrorCode.ORDER_QUANTITY_INVALID);
			assertRejected(() -> orderService.place(userId, buy(SAMSUNG, -1)), OrderErrorCode.ORDER_QUANTITY_INVALID);
			assertNothingHappened(userId);
		}

		/** §7.2 는 거래시간이 1번이지만 §11.2 는 종목 존재가 먼저다 — 없는 종목에 "장 마감" 을 말하지 않는다. */
		@Test
		@DisplayName("없는 종목은 장 마감이어도 STOCK_NOT_FOUND")
		void stockBeforeMarket() {
			Long userId = fundedUser(FUNDED);
			given(marketClock.isOpen()).willReturn(false);

			assertRejected(() -> orderService.place(userId, buy("999999", 1)), StockErrorCode.STOCK_NOT_FOUND);
			assertNothingHappened(userId);
		}

		@Test
		@DisplayName("거래정지는 장 마감·시세 없음이 겹쳐도 ORDER_STOCK_SUSPENDED 이고 detail.reason 을 싣는다")
		void suspendedBeforeMarket() {
			Long userId = fundedUser(FUNDED);
			String code = suspendedStock("ZZ9910", "관리종목");
			given(marketClock.isOpen()).willReturn(false);

			assertThatThrownBy(() -> orderService.place(userId, buy(code, 1)))
				.isInstanceOf(CustomException.class)
				.satisfies(e -> {
					CustomException ce = (CustomException) e;
					assertThat(ce.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_STOCK_SUSPENDED);
					assertThat(ce.getDetail()).isEqualTo(Map.of("reason", "관리종목"));
				});
			assertNothingHappened(userId);
		}

		/** backend_story S9 완료 조건 — always-open=false 인 장외 시간에는 409 다. 시계가 "닫힘" 이면 시세가 있어도 막힌다. */
		@Test
		@DisplayName("장외 시간은 시세가 있어도 ORDER_MARKET_CLOSED (409)")
		void marketClosed() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);
			given(marketClock.isOpen()).willReturn(false);

			assertRejected(() -> orderService.place(userId, buy(SAMSUNG, 1)), OrderErrorCode.ORDER_MARKET_CLOSED);
			assertThat(OrderErrorCode.ORDER_MARKET_CLOSED.getStatus().value()).isEqualTo(409);
			assertNothingHappened(userId);
		}

		@Test
		@DisplayName("시세 없음은 ORDER_PRICE_UNAVAILABLE (503)")
		void priceMissing() {
			Long userId = fundedUser(FUNDED);
			String code = freshStock("ZZ9911");

			assertRejected(() -> orderService.place(userId, buy(code, 1)), OrderErrorCode.ORDER_PRICE_UNAVAILABLE);
			assertThat(OrderErrorCode.ORDER_PRICE_UNAVAILABLE.getStatus().value()).isEqualTo(503);
			assertNothingHappened(userId);
		}

		@Test
		@DisplayName("시세가 stale(허용 시간 초과)이면 값이 있어도 ORDER_PRICE_UNAVAILABLE")
		void priceStale() {
			Long userId = fundedUser(FUNDED);
			priceCache.put(SAMSUNG, new PriceEntry(70_000L, 70_000L,
				Instant.now().minus(priceProperties.staleAfter()).minusSeconds(1)));

			assertRejected(() -> orderService.place(userId, buy(SAMSUNG, 1)), OrderErrorCode.ORDER_PRICE_UNAVAILABLE);
			assertNothingHappened(userId);
		}

		@Test
		@DisplayName("매수 — 예수금 부족은 ORDER_INSUFFICIENT_CASH 이고 detail 은 잠근 잔고 기준 {required, available}")
		void insufficientCash() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);

			assertThatThrownBy(() -> orderService.place(userId, buy(SAMSUNG, 15)))
				.isInstanceOf(CustomException.class)
				.satisfies(e -> {
					CustomException ce = (CustomException) e;
					assertThat(ce.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INSUFFICIENT_CASH);
					assertThat(ce.getDetail()).isEqualTo(Map.of("required", 1_050_000L, "available", FUNDED));
				});
			assertNothingHappened(userId);
		}

		@Test
		@DisplayName("매도 — 보유 부족은 ORDER_INSUFFICIENT_QUANTITY 이고 원장·trade 가 남지 않는다. 미보유는 available 0")
		void insufficientQuantity() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);
			orderService.place(userId, buy(SAMSUNG, 4));

			assertThatThrownBy(() -> orderService.place(userId, sell(SAMSUNG, 5)))
				.isInstanceOf(CustomException.class)
				.satisfies(e -> {
					CustomException ce = (CustomException) e;
					assertThat(ce.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INSUFFICIENT_QUANTITY);
					assertThat(ce.getDetail()).isEqualTo(Map.of("required", 5L, "available", 4L));
				});
			price("000660", 100_000);
			assertThatThrownBy(() -> orderService.place(userId, sell("000660", 1)))
				.isInstanceOf(CustomException.class)
				.satisfies(e -> assertThat(((CustomException) e).getDetail())
					.isEqualTo(Map.of("required", 1L, "available", 0L)));

			Account account = account(userId);
			assertThat(tradeRepository.findByAccountIdOrderByIdDesc(account.getId())).hasSize(1);
			assertThat(account.getCashBalance()).isEqualTo(FUNDED - 280_000);
		}
	}

	@Nested
	@DisplayName("동시성")
	class Concurrency {

		/**
		 * <b>이 스토리의 필수 조건이다</b> (backend_story S9). 예수금 100만에 7만원 종목 10주 매수 5개가 동시에 와도 계좌 FOR UPDATE 가
		 * 줄을 세워 하나만 통과하고 나머지는 잠근 값(30만)으로 부족 판정을 받는다. 락이 없으면 다섯이 100만을 읽어 전부 통과하고
		 * 잔고가 −250만이 된다 — DB CHECK 가 막더라도 그건 마지막 방어선이지 설계가 아니다.
		 */
		@Test
		@DisplayName("동시 매수 — 100만 예수금에 70만 매수 5건 동시 → 성공 1건, 나머지 ORDER_INSUFFICIENT_CASH, cash_balance ≥ 0, 불변식 1")
		void concurrentBuysSerialize() throws Exception {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);

			List<Outcome> outcomes = concurrently(5, () -> orderService.place(userId, buy(SAMSUNG, 10)));

			List<Outcome> succeeded = outcomes.stream().filter(o -> o.res() != null).toList();
			List<Outcome> rejected = outcomes.stream().filter(o -> o.error() != null).toList();
			assertThat(succeeded).hasSize(1);
			assertThat(succeeded.getFirst().res().cashBalanceAfter()).isEqualTo(300_000);
			assertThat(rejected).hasSize(4)
				.allMatch(o -> o.error().getErrorCode() == OrderErrorCode.ORDER_INSUFFICIENT_CASH)
				// 잠근 뒤 읽은 잔고라 첫 체결이 반영된 값이다.
				.allMatch(o -> o.error().getDetail().equals(Map.of("required", 700_000L, "available", 300_000L)));

			Account account = account(userId);
			assertThat(account.getCashBalance()).isEqualTo(300_000).isNotNegative();
			List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId());
			assertThat(account.getCashBalance()).isEqualTo(entries.stream().mapToLong(LedgerEntry::getCashDelta).sum());
			assertThat(tradeRepository.findByAccountIdOrderByIdDesc(account.getId())).hasSize(1);
			assertThat(holding(account.getId(), SAMSUNG).getQuantity()).isEqualTo(10);
		}

		@Test
		@DisplayName("동시 매도 — 10주 보유에 10주 매도 5건 동시 → 성공 1건, 나머지 ORDER_INSUFFICIENT_QUANTITY, 보유 0, 불변식 3")
		void concurrentSellsSerialize() throws Exception {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);
			orderService.place(userId, buy(SAMSUNG, 10));

			List<Outcome> outcomes = concurrently(5, () -> orderService.place(userId, sell(SAMSUNG, 10)));

			List<Outcome> succeeded = outcomes.stream().filter(o -> o.res() != null).toList();
			List<Outcome> rejected = outcomes.stream().filter(o -> o.error() != null).toList();
			assertThat(succeeded).hasSize(1);
			assertThat(rejected).hasSize(4)
				.allMatch(o -> o.error().getErrorCode() == OrderErrorCode.ORDER_INSUFFICIENT_QUANTITY)
				.allMatch(o -> o.error().getDetail().equals(Map.of("required", 10L, "available", 0L)));

			Account account = account(userId);
			assertThat(account.getCashBalance()).isEqualTo(FUNDED);
			assertThat(holding(account.getId(), SAMSUNG).getQuantity()).isZero();
			assertThat(tradeRepository.findByAccountIdOrderByIdDesc(account.getId())).hasSize(2);
		}
	}

	@Nested
	@DisplayName("주문 가능 정보 (apiSpec 7.3)")
	class Available {

		@Test
		@DisplayName("정상 — tradable, currentPrice, availableCash, 매수 maxQuantity = floor(cash/price), holdingQuantity")
		void tradable() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);
			orderService.place(userId, buy(SAMSUNG, 3));
			price(SAMSUNG, 73_500);

			OrderAvailableRes res = orderService.available(userId, SAMSUNG, OrderSide.BUY);

			assertThat(res.tradable()).isTrue();
			assertThat(res.reason()).isNull();
			assertThat(res.currentPrice()).isEqualTo(73_500);
			assertThat(res.availableCash()).isEqualTo(790_000);
			// 790,000 / 73,500 = 10.74… → 10
			assertThat(res.maxQuantity()).isEqualTo(10);
			assertThat(res.holdingQuantity()).isEqualTo(3);
		}

		@Test
		@DisplayName("매도 side 는 maxQuantity 가 보유 수량이고, 미보유면 0")
		void sellSideUsesHolding() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);
			orderService.place(userId, buy(SAMSUNG, 3));

			assertThat(orderService.available(userId, SAMSUNG, OrderSide.SELL).maxQuantity()).isEqualTo(3);
			price("000660", 100_000);
			assertThat(orderService.available(userId, "000660", OrderSide.SELL).maxQuantity()).isZero();
		}

		@Test
		@DisplayName("장외 — tradable false, reason ORDER_MARKET_CLOSED, 시세와 maxQuantity 는 그대로 준다")
		void marketClosed() {
			Long userId = fundedUser(FUNDED);
			price(SAMSUNG, 70_000);
			given(marketClock.isOpen()).willReturn(false);

			OrderAvailableRes res = orderService.available(userId, SAMSUNG, OrderSide.BUY);

			assertThat(res.tradable()).isFalse();
			assertThat(res.reason()).isEqualTo("ORDER_MARKET_CLOSED");
			assertThat(res.currentPrice()).isEqualTo(70_000);
			assertThat(res.maxQuantity()).isEqualTo(14);
		}

		@Test
		@DisplayName("거래정지 — reason ORDER_STOCK_SUSPENDED")
		void suspended() {
			Long userId = fundedUser(FUNDED);
			String code = suspendedStock("ZZ9912", "관리종목");
			price(code, 1_000);

			OrderAvailableRes res = orderService.available(userId, code, OrderSide.BUY);

			assertThat(res.tradable()).isFalse();
			assertThat(res.reason()).isEqualTo("ORDER_STOCK_SUSPENDED");
		}

		@Test
		@DisplayName("시세 없음 — reason ORDER_PRICE_UNAVAILABLE, currentPrice null, maxQuantity 0")
		void priceUnavailable() {
			Long userId = fundedUser(FUNDED);
			String code = freshStock("ZZ9913");

			OrderAvailableRes res = orderService.available(userId, code, OrderSide.BUY);

			assertThat(res.tradable()).isFalse();
			assertThat(res.reason()).isEqualTo("ORDER_PRICE_UNAVAILABLE");
			assertThat(res.currentPrice()).isNull();
			assertThat(res.maxQuantity()).isZero();
			assertThat(res.availableCash()).isEqualTo(FUNDED);
		}

		@Test
		@DisplayName("없는 종목만 에러다 — STOCK_NOT_FOUND")
		void stockNotFound() {
			Long userId = fundedUser(FUNDED);

			assertRejected(() -> orderService.available(userId, "999999", OrderSide.BUY), StockErrorCode.STOCK_NOT_FOUND);
		}
	}

	private record Outcome(OrderRes res, CustomException error) {
	}

	// ---- helpers ----

	private List<Outcome> concurrently(int concurrency, Callable<OrderRes> order) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		Callable<Outcome> call = () -> {
			start.await();
			try {
				return new Outcome(order.call(), null);
			} catch (CustomException e) {
				return new Outcome(null, e);
			}
		};
		List<Outcome> outcomes = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
			// invokeAll 은 전부 끝날 때까지 막으므로 쓰지 않는다 — 래치를 풀 기회가 없어 교착된다.
			List<Future<Outcome>> futures = new ArrayList<>();
			for (int i = 0; i < concurrency; i++) {
				futures.add(pool.submit(call));
			}
			start.countDown();
			for (Future<Outcome> future : futures) {
				outcomes.add(future.get());
			}
		}
		return outcomes;
	}

	private static OrderReq buy(String stockCode, long quantity) {
		return new OrderReq(stockCode, OrderSide.BUY, quantity);
	}

	private static OrderReq sell(String stockCode, long quantity) {
		return new OrderReq(stockCode, OrderSide.SELL, quantity);
	}

	/** 정상 수신 상태의 시세. {@code asOf} 가 지금이라 stale-after(10초) 안이다. */
	private void price(String stockCode, long currentPrice) {
		priceCache.put(stockCode, new PriceEntry(currentPrice, currentPrice, Instant.now()));
	}

	private String suspendedStock(String code, String reason) {
		transactionTemplate.executeWithoutResult(s -> stockRepository.save(
			Stock.of(code, "정지종목", Market.KOSPI, true, reason, 1_000L, Instant.now())));
		return code;
	}

	/** 활성이지만 시세 캐시에 없는 종목. 시드 종목은 다른 테스트가 시세를 넣었을 수 있어 새 코드를 쓴다. */
	private String freshStock(String code) {
		transactionTemplate.executeWithoutResult(s -> stockRepository.save(
			Stock.of(code, "신규종목", Market.KOSDAQ, false, null, 1_000L, Instant.now())));
		return code;
	}

	private static void assertRejected(Runnable action, BaseErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOf(CustomException.class)
			.extracting(e -> ((CustomException) e).getErrorCode())
			.isEqualTo(expected);
	}

	/** 거절은 네 곳 어디에도 흔적을 남기지 않는다 — 원장은 충전 1행뿐, trade·holding 없음, 잔고 그대로. */
	private void assertNothingHappened(Long userId) {
		Account account = account(userId);
		assertThat(account.getCashBalance()).isEqualTo(FUNDED);
		assertThat(ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId())).hasSize(1);
		assertThat(tradeRepository.findByAccountIdOrderByIdDesc(account.getId())).isEmpty();
		assertThat(holdingRepository.findHeld(account.getId())).isEmpty();
	}

	/** 계좌를 열고 충전으로 예수금을 만든다. 1회 한도(1천만) 안이어야 한다. */
	private Long fundedUser(long amount) {
		User user = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg"));
		accountService.ensureAccount(user.getId());
		Long paymentId = depositService.ready(user.getId(), PaymentMethod.TRANSFER, amount).paymentId();
		MockApproveRes approved = depositService.mockApprove(user.getId(), paymentId, MockScenario.SUCCESS);
		depositService.confirm(user.getId(), approved.paymentId(), approved.paymentKey(), amount);
		return user.getId();
	}

	private Account account(Long userId) {
		return accountRepository.findByUserId(userId).orElseThrow();
	}

	private Holding holding(Long accountId, String stockCode) {
		return holdingRepository.findByAccountIdAndStockCode(accountId, stockCode).orElseThrow();
	}
}
