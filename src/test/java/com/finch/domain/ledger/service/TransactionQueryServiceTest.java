package com.finch.domain.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.repository.AccountRepository;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.deposit.dto.response.MockApproveRes;
import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.domain.deposit.gateway.MockScenario;
import com.finch.domain.deposit.service.DepositService;
import com.finch.domain.ledger.dto.request.TransactionFilter;
import com.finch.domain.ledger.dto.response.TransactionRes;
import com.finch.domain.ledger.entity.LedgerEntry;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.LedgerEntryRepository;
import com.finch.domain.withdrawal.service.WithdrawalService;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.exception.CustomException;
import com.finch.global.paging.CursorCodec;
import com.finch.global.paging.CursorPage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 매매 내역 조회가 실제 DB 에서 무엇을 돌려주는지 본다 — 조인·필터·정렬·커서는 전부 SQL 에 있어 목으로는 볼 수 없다.
 * <p>
 * 원장은 <b>정상 경로로</b> 만든다: 충전은 {@code DepositService}, 출금은 {@code WithdrawalService}. 체결(trade)과
 * 종목(stock)은 아직 서비스가 없어(S5·S9) {@code JdbcTemplate} 로 행을 직접 넣는다 — 원장 행은 {@code LedgerService}
 * 로 남기고 trade 는 그 id 에 매단다. {@code INITIAL_GRANT} 는 S1 부터 발행되지 않으므로 원장 행을 직접 넣어 "지급
 * 정책이 되살아나도 필터가 깨지지 않는다"를 고정한다 (backend_story S4).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransactionQueryServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(940_000_000L);
	private static final AtomicLong STOCK_SEQ = new AtomicLong(100_000L);

	@Autowired
	private TransactionQueryService transactionQueryService;

	@Autowired
	private DepositService depositService;

	@Autowired
	private WithdrawalService withdrawalService;

	@Autowired
	private LedgerService ledgerService;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private CursorCodec cursorCodec;

	@Nested
	@DisplayName("필터")
	class Filter {

		@Test
		@DisplayName("ALL 은 원장 5종 전부를 최신순(id DESC)으로 준다 — 유형별로 채워지는 필드가 다르다")
		void allInDescendingOrder() {
			Long userId = newUserId();
			String code = stock("삼성전자");
			deposit(userId, 1_000_000L, PaymentMethod.TRANSFER);
			buy(userId, code, 5, 70_000L);
			sell(userId, code, 5, 73_500L, 70_000L);
			withdraw(userId, 200_000L);
			initialGrant(userId, 50_000L);

			List<TransactionRes> items = transactionQueryService.list(userId, TransactionFilter.ALL, null, 30).items();

			assertThat(items).extracting(TransactionRes::type).containsExactly(LedgerType.INITIAL_GRANT,
				LedgerType.WITHDRAWAL, LedgerType.SELL, LedgerType.BUY, LedgerType.DEPOSIT);
			assertThat(items).extracting(TransactionRes::transactionId).isSortedAccordingTo((a, b) -> Long.compare(b, a));

			TransactionRes grant = items.get(0);
			assertThat(grant.amount()).isEqualTo(50_000L);
			assertThat(grant.paymentMethod()).isNull();
			assertThat(grant.stockCode()).isNull();

			TransactionRes withdrawal = items.get(1);
			assertThat(withdrawal.amount()).isEqualTo(200_000L);
			assertThat(withdrawal.paymentMethod()).isNull();
			assertThat(withdrawal.stockCode()).isNull();

			TransactionRes sell = items.get(2);
			assertThat(sell.stockCode()).isEqualTo(code);
			assertThat(sell.stockName()).isEqualTo("삼성전자");
			assertThat(sell.price()).isEqualTo(73_500L);
			assertThat(sell.quantity()).isEqualTo(5L);
			assertThat(sell.amount()).isEqualTo(367_500L);
			assertThat(sell.realizedProfit()).isEqualTo(17_500L);
			// 17,500 / (70,000 × 5) × 100 = 5.00
			assertThat(sell.realizedProfitRate()).isEqualByComparingTo(new BigDecimal("5.00"));

			TransactionRes buy = items.get(3);
			assertThat(buy.stockName()).isEqualTo("삼성전자");
			assertThat(buy.amount()).isEqualTo(350_000L);
			assertThat(buy.realizedProfit()).isNull();
			assertThat(buy.realizedProfitRate()).isNull();
			assertThat(buy.paymentMethod()).isNull();

			TransactionRes deposit = items.get(4);
			assertThat(deposit.amount()).isEqualTo(1_000_000L);
			assertThat(deposit.paymentMethod()).isEqualTo("TRANSFER");
			assertThat(deposit.stockCode()).isNull();
			// apiSpec 1.1 — 시각은 KST 오프셋을 포함한다.
			assertThat(deposit.occurredAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
		}

		/** featureSpec 8 — 이 필터의 합계가 충전 한도 화면의 누적 충전액과 같아야 한다. 출금·초기 지급이 섞이면 어긋난다. */
		@Test
		@DisplayName("DEPOSIT 은 충전만이다 — WITHDRAWAL·INITIAL_GRANT 가 없고 합계는 depositedAmount 와 같다")
		void depositFilterExcludesWithdrawalAndGrant() {
			Long userId = newUserId();
			deposit(userId, 1_000_000L, PaymentMethod.TRANSFER);
			deposit(userId, 300_000L, PaymentMethod.KAKAOPAY);
			withdraw(userId, 500_000L);
			initialGrant(userId, 50_000L);

			List<TransactionRes> items = transactionQueryService.list(userId, TransactionFilter.DEPOSIT, null, 30).items();

			assertThat(items).hasSize(2).allMatch(i -> i.type() == LedgerType.DEPOSIT);
			assertThat(items).extracting(TransactionRes::paymentMethod).containsExactly("KAKAOPAY", "TRANSFER");
			assertThat(items.stream().mapToLong(TransactionRes::amount).sum())
				.isEqualTo(depositService.getLimit(userId).depositedAmount())
				.isEqualTo(1_300_000L);
		}

		@Test
		@DisplayName("WITHDRAWAL 은 출금만이다 — DEPOSIT 이 없고 amount 는 양수 절대값이다")
		void withdrawalFilterExcludesDeposit() {
			Long userId = newUserId();
			deposit(userId, 1_000_000L, PaymentMethod.TRANSFER);
			withdraw(userId, 300_000L);
			withdraw(userId, 200_000L);

			List<TransactionRes> items = transactionQueryService.list(userId, TransactionFilter.WITHDRAWAL, null, 30)
				.items();

			assertThat(items).hasSize(2).allMatch(i -> i.type() == LedgerType.WITHDRAWAL);
			assertThat(items).extracting(TransactionRes::amount).containsExactly(200_000L, 300_000L);
			assertThat(items).allMatch(i -> i.amount() > 0);
			assertThat(items).allMatch(i -> i.paymentMethod() == null);
		}

		@Test
		@DisplayName("BUY·SELL 은 각각 그 체결만이다")
		void tradeFilters() {
			Long userId = newUserId();
			String code = stock("카카오");
			deposit(userId, 1_000_000L, PaymentMethod.TRANSFER);
			buy(userId, code, 2, 50_000L);
			buy(userId, code, 3, 52_000L);
			sell(userId, code, 1, 55_000L, 51_200L);

			assertThat(transactionQueryService.list(userId, TransactionFilter.BUY, null, 30).items())
				.hasSize(2).allMatch(i -> i.type() == LedgerType.BUY);
			List<TransactionRes> sells = transactionQueryService.list(userId, TransactionFilter.SELL, null, 30).items();
			assertThat(sells).hasSize(1);
			// (55,000 − 51,200) × 1 = 3,800 / 51,200 × 100 = 7.421… → 7.42
			assertThat(sells.getFirst().realizedProfit()).isEqualTo(3_800L);
			assertThat(sells.getFirst().realizedProfitRate()).isEqualByComparingTo(new BigDecimal("7.42"));
		}
	}

	@Nested
	@DisplayName("페이징")
	class Paging {

		@Test
		@DisplayName("size 만큼 자르고 커서로 이어 읽으면 겹침·누락 없이 전부 읽히고 마지막 페이지의 nextCursor 는 null 이다")
		void pagesThroughWithoutGapOrOverlap() {
			Long userId = newUserId();
			deposit(userId, 1_000_000L, PaymentMethod.TRANSFER);
			for (int i = 0; i < 6; i++) {
				withdraw(userId, 10_000L);
			}
			// 원장 7행. size 3 → 3 · 3 · 1.

			List<TransactionRes> all = new ArrayList<>();
			String cursor = null;
			int pages = 0;
			do {
				CursorPage<TransactionRes> page = transactionQueryService.list(userId, TransactionFilter.ALL, cursor, 3);
				pages++;
				all.addAll(page.items());
				if (page.hasNext()) {
					assertThat(page.items()).hasSize(3);
					assertThat(page.nextCursor()).isNotNull();
				} else {
					assertThat(page.nextCursor()).isNull();
				}
				cursor = page.nextCursor();
			} while (cursor != null);

			assertThat(pages).isEqualTo(3);
			assertThat(all).hasSize(7);
			assertThat(all).extracting(TransactionRes::transactionId).doesNotHaveDuplicates()
				.isSortedAccordingTo((a, b) -> Long.compare(b, a));
			assertThat(all.getLast().type()).isEqualTo(LedgerType.DEPOSIT);
		}

		@Test
		@DisplayName("정확히 size 건이면 마지막 페이지다 — hasNext false, nextCursor null")
		void exactSizeIsLastPage() {
			Long userId = newUserId();
			deposit(userId, 1_000_000L, PaymentMethod.TRANSFER);
			withdraw(userId, 10_000L);

			CursorPage<TransactionRes> page = transactionQueryService.list(userId, TransactionFilter.ALL, null, 2);

			assertThat(page.items()).hasSize(2);
			assertThat(page.hasNext()).isFalse();
			assertThat(page.nextCursor()).isNull();
		}

		@Test
		@DisplayName("커서는 유형 필터와 함께 동작한다 — 필터 안에서 이어 읽는다")
		void cursorWorksWithFilter() {
			Long userId = newUserId();
			deposit(userId, 1_000_000L, PaymentMethod.TRANSFER);
			for (int i = 0; i < 3; i++) {
				withdraw(userId, 10_000L);
				deposit(userId, 10_000L, PaymentMethod.TRANSFER);
			}

			CursorPage<TransactionRes> first = transactionQueryService.list(userId, TransactionFilter.WITHDRAWAL, null, 2);
			CursorPage<TransactionRes> second = transactionQueryService.list(userId, TransactionFilter.WITHDRAWAL,
				first.nextCursor(), 2);

			assertThat(first.items()).hasSize(2).allMatch(i -> i.type() == LedgerType.WITHDRAWAL);
			assertThat(second.items()).hasSize(1).allMatch(i -> i.type() == LedgerType.WITHDRAWAL);
			assertThat(second.hasNext()).isFalse();
			assertThat(second.items().getFirst().transactionId()).isLessThan(first.items().getLast().transactionId());
		}

		@Test
		@DisplayName("손상된 커서는 INVALID_REQUEST 다 — 첫 페이지로 되돌리지 않는다")
		void rejectsCorruptedCursor() {
			Long userId = newUserId();

			assertThatThrownBy(() -> transactionQueryService.list(userId, TransactionFilter.ALL, "not-base64!", 30))
				.isInstanceOf(CustomException.class)
				.extracting(e -> ((CustomException) e).getErrorCode())
				.isEqualTo(GeneralErrorCode.INVALID_REQUEST);
			// Base64 는 맞지만 id 가 없는 JSON 도 마찬가지다.
			String noId = java.util.Base64.getEncoder().encodeToString("{}".getBytes());
			assertThatThrownBy(() -> transactionQueryService.list(userId, TransactionFilter.ALL, noId, 30))
				.isInstanceOf(CustomException.class);
		}
	}

	@Nested
	@DisplayName("경계")
	class Boundary {

		/** apiSpec 8.2 — 갓 가입한 계정은 원장이 비어 있다. 에러가 아니라 정상 초기 상태다. */
		@Test
		@DisplayName("갓 가입한 계정은 빈 목록이다 — items [], nextCursor null, hasNext false")
		void freshAccountIsEmpty() {
			Long userId = newUserId();

			for (TransactionFilter filter : TransactionFilter.values()) {
				CursorPage<TransactionRes> page = transactionQueryService.list(userId, filter, null, 30);
				assertThat(page.items()).isEmpty();
				assertThat(page.nextCursor()).isNull();
				assertThat(page.hasNext()).isFalse();
			}
		}

		/** 계좌는 토큰의 사용자로만 찾는다 (apiSpec 1.6). 남의 원장은 어느 필터로도 보이지 않는다. */
		@Test
		@DisplayName("다른 사용자의 내역은 보이지 않는다")
		void doesNotLeakOtherUsersLedger() {
			Long rich = newUserId();
			Long other = newUserId();
			deposit(rich, 1_000_000L, PaymentMethod.TRANSFER);
			withdraw(rich, 100_000L);
			deposit(other, 5_000L, PaymentMethod.TRANSFER);

			List<TransactionRes> items = transactionQueryService.list(other, TransactionFilter.ALL, null, 30).items();

			assertThat(items).hasSize(1);
			assertThat(items.getFirst().amount()).isEqualTo(5_000L);
			assertThat(transactionQueryService.list(other, TransactionFilter.WITHDRAWAL, null, 30).items()).isEmpty();
		}

		/** 남의 커서를 들고 와도 내 원장의 "그 id 보다 작은 것"일 뿐이다 — 커서는 사용자를 담지 않고 조건은 사용자로 먼저 좁힌다. */
		@Test
		@DisplayName("남의 커서로 요청해도 내 내역만 나온다")
		void foreignCursorStillScopedToMe() {
			Long rich = newUserId();
			Long other = newUserId();
			deposit(other, 5_000L, PaymentMethod.TRANSFER);
			deposit(rich, 1_000_000L, PaymentMethod.TRANSFER);
			withdraw(rich, 100_000L);
			String richCursor = cursorCodec.encode(
				transactionQueryService.list(rich, TransactionFilter.ALL, null, 30).items().getFirst().transactionId());

			List<TransactionRes> items = transactionQueryService.list(other, TransactionFilter.ALL, richCursor, 30).items();

			assertThat(items).hasSize(1);
			assertThat(items.getFirst().amount()).isEqualTo(5_000L);
		}
	}

	// ---- helpers ----

	/**
	 * 충전 한 건. TRANSFER 는 모의 승인, KAKAOPAY 는 승인 콜백을 거친다 — mock-approve 는 TRANSFER 전용이다 (apiSpec 4.3.2).
	 * 테스트 설정({@code kakaopay.enabled=false})이라 콜백도 카카오를 치지 않고 {@code MockTransferGateway} 가 승인한다.
	 */
	private void deposit(Long userId, long amount, PaymentMethod method) {
		Long paymentId = depositService.ready(userId, method, amount).paymentId();
		String paymentKey;
		if (method == PaymentMethod.TRANSFER) {
			MockApproveRes approved = depositService.mockApprove(userId, paymentId, MockScenario.SUCCESS);
			paymentKey = approved.paymentKey();
		} else {
			String redirect = depositService.kakaoApproval(paymentId, "pg-token");
			paymentKey = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("paymentKey");
			assertThat(paymentKey).as("성공 URL 에 paymentKey 가 있어야 한다: " + redirect).isNotNull();
		}
		depositService.confirm(userId, paymentId, paymentKey, amount);
	}

	private void withdraw(Long userId, long amount) {
		withdrawalService.withdraw(userId, amount);
	}

	/** 종목 마스터 행. S5 전이라 직접 넣는다. 코드는 테스트마다 다르게 만든다 — PK 충돌을 피한다. */
	private String stock(String name) {
		String code = String.valueOf(STOCK_SEQ.incrementAndGet());
		jdbcTemplate.update("INSERT INTO stock (stock_code, stock_name, market, updated_at) VALUES (?, ?, 'KOSPI', now())",
			code, name);
		return code;
	}

	private void buy(Long userId, String stockCode, long quantity, long price) {
		trade(userId, stockCode, "BUY", quantity, price, null, null);
	}

	private void sell(Long userId, String stockCode, long quantity, long price, long avgBuyPrice) {
		trade(userId, stockCode, "SELL", quantity, price, avgBuyPrice, (price - avgBuyPrice) * quantity);
	}

	/**
	 * 체결 한 건 — 원장 행은 {@code LedgerService} 로, trade 행은 SQL 로. S9 가 만들 트랜잭션의 모양을 흉내 낸다.
	 * 잔고는 마지막 원장 행의 {@code cash_balance_after} 에서 이어 간다 ({@code ck_ledger_balance_after >= 0}).
	 */
	private void trade(Long userId, String stockCode, String side, long quantity, long price, Long avgBuyPrice,
		Long realizedProfit) {
		Long accountId = accountRepository.findByUserId(userId).orElseThrow().getId();
		long amount = quantity * price;
		long delta = side.equals("BUY") ? -amount : amount;
		transactionTemplate.executeWithoutResult(status -> {
			long after = lastBalance(accountId) + delta;
			Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
			long ledgerId = ledgerService.record(accountId, LedgerType.valueOf(side), delta, after, now).id();
			jdbcTemplate.update("""
				INSERT INTO trade (ledger_entry_id, account_id, stock_code, side, quantity, executed_price,
				                   executed_amount, avg_buy_price, realized_profit, executed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", ledgerId, accountId, stockCode, side, quantity, price, amount, avgBuyPrice, realizedProfit,
				java.sql.Timestamp.from(now));
		});
	}

	/** 초기 지급 원장 행. S1 부터 발행되지 않아 서비스로는 만들 수 없다 — 지급 정책이 되살아났을 때의 모양을 직접 넣는다. */
	private void initialGrant(Long userId, long amount) {
		Long accountId = accountRepository.findByUserId(userId).orElseThrow().getId();
		transactionTemplate.executeWithoutResult(status ->
			ledgerService.record(accountId, LedgerType.INITIAL_GRANT, amount, lastBalance(accountId) + amount,
				Instant.now().truncatedTo(ChronoUnit.MICROS)));
	}

	private long lastBalance(Long accountId) {
		List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByIdDesc(accountId);
		return entries.isEmpty() ? 0L : entries.getFirst().getCashBalanceAfter();
	}

	private Long newUserId() {
		User user = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg"));
		accountService.ensureAccount(user.getId());
		return user.getId();
	}
}
