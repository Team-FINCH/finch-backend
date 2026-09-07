package com.finch.domain.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.dto.response.AccountRes;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.portfolio.dto.request.PortfolioSort;
import com.finch.domain.portfolio.dto.response.PortfolioRes;
import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보유 목록이 화면에 나가는 모양 (apiSpec 8.1). 정렬·시세 없음·{@code asOf} 세 가지가 이 스토리가 정한 규칙이고 나머지는
 * {@code Valuation} 이 이미 확인한다.
 * <p>
 * 시세 포트는 목이다 — 실제 캐시는 공급자가 채우는 값이라 테스트가 원하는 가격을 넣을 수 없다. 보유와 계좌는 실제 DB 다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PortfolioQueryServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(990_000_000L);
	private static final String SAMSUNG = "005930";
	private static final String HYNIX = "000660";
	private static final String NAVER = "035420";

	@Autowired
	private PortfolioQueryService portfolioQueryService;

	@Autowired
	private HoldingCommandService holdingCommandService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private PriceQueryPort priceQueryPort;

	@Nested
	@DisplayName("목록")
	class Listing {

		@Test
		@DisplayName("보유가 없으면 빈 배열이다 — 에러가 아니라 신규 사용자의 정상 상태다")
		void returnsEmpty() {
			Long userId = newUser();

			PortfolioRes res = portfolioQueryService.list(userId, PortfolioSort.EVALUATION);

			assertThat(res.holdings()).isEmpty();
			assertThat(res.evaluationAmount()).isZero();
			assertThat(res.totalAsset()).isEqualTo(res.cashBalance());
			// 평가할 것이 없어도 갱신 시각은 비어 보이지 않는다.
			assertThat(res.asOf()).isNotNull();
			assertThat(res.asOf().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
		}

		@Test
		@DisplayName("평가금액·평가손익·수익률을 종목마다 계산하고 합계는 서버가 더한다")
		void computesValuation() {
			Long userId = newUser();
			buy(userId, SAMSUNG, 10, 70_000);
			givenPrices(Map.of(SAMSUNG, 80_000L));

			PortfolioRes res = portfolioQueryService.list(userId, PortfolioSort.EVALUATION);

			PortfolioRes.Holding samsung = res.holdings().getFirst();
			assertThat(samsung.stockName()).isEqualTo("삼성전자");
			assertThat(samsung.quantity()).isEqualTo(10);
			assertThat(samsung.avgBuyPrice()).isEqualTo(70_000);
			assertThat(samsung.currentPrice()).isEqualTo(80_000);
			assertThat(samsung.evaluationAmount()).isEqualTo(800_000);
			assertThat(samsung.evaluationProfit()).isEqualTo(100_000);
			assertThat(samsung.evaluationProfitRate()).isEqualByComparingTo("14.29");
			assertThat(res.evaluationAmount()).isEqualTo(800_000);
			assertThat(res.totalAsset()).isEqualTo(res.cashBalance() + 800_000);
		}

		@Test
		@DisplayName("전량 매도한 종목은 목록에서 사라진다 — 행은 남아 있어도 화면에는 없다")
		void hidesZeroedHolding() {
			Long userId = newUser();
			buy(userId, SAMSUNG, 10, 70_000);
			sell(userId, SAMSUNG, 10, 80_000);
			givenPrices(Map.of(SAMSUNG, 80_000L));

			assertThat(portfolioQueryService.list(userId, PortfolioSort.EVALUATION).holdings()).isEmpty();
		}
	}

	@Nested
	@DisplayName("정렬")
	class Sorting {

		@Test
		@DisplayName("기본은 평가금액 내림차순")
		void sortsByEvaluation() {
			Long userId = newUser();
			buy(userId, SAMSUNG, 1, 10_000);   // 평가 100,000
			buy(userId, HYNIX, 1, 10_000);     // 평가 300,000
			buy(userId, NAVER, 1, 10_000);     // 평가 200,000
			givenPrices(Map.of(SAMSUNG, 100_000L, HYNIX, 300_000L, NAVER, 200_000L));

			assertThat(codes(userId, PortfolioSort.EVALUATION)).containsExactly(HYNIX, NAVER, SAMSUNG);
		}

		@Test
		@DisplayName("PROFIT_RATE 는 수익률 내림차순 — 평가금액 순서와 다르다")
		void sortsByProfitRate() {
			Long userId = newUser();
			buy(userId, SAMSUNG, 1, 10_000);   // +900%,  평가 100,000
			buy(userId, HYNIX, 10, 29_000);    // +3.45%, 평가 300,000
			buy(userId, NAVER, 1, 100_000);    // +100%,  평가 200,000
			givenPrices(Map.of(SAMSUNG, 100_000L, HYNIX, 30_000L, NAVER, 200_000L));

			assertThat(codes(userId, PortfolioSort.PROFIT_RATE)).containsExactly(SAMSUNG, NAVER, HYNIX);
			assertThat(codes(userId, PortfolioSort.EVALUATION)).containsExactly(HYNIX, NAVER, SAMSUNG);
		}
	}

	@Nested
	@DisplayName("시세가 없는 종목")
	class MissingPrice {

		@Test
		@DisplayName("평가 네 필드가 전부 null 이고 수량·평단은 그대로 나간다")
		void nullsValuationFields() {
			Long userId = newUser();
			buy(userId, SAMSUNG, 10, 70_000);
			givenPrices(Map.of());

			PortfolioRes.Holding samsung = portfolioQueryService.list(userId, PortfolioSort.EVALUATION)
				.holdings().getFirst();

			assertThat(samsung.currentPrice()).isNull();
			assertThat(samsung.evaluationAmount()).isNull();
			assertThat(samsung.evaluationProfit()).isNull();
			assertThat(samsung.evaluationProfitRate()).isNull();
			assertThat(samsung.quantity()).isEqualTo(10);
			assertThat(samsung.avgBuyPrice()).isEqualTo(70_000);
		}

		@Test
		@DisplayName("합계에 더하지 않는다 — 0 으로 치면 그 종목의 자산이 사라진 것처럼 보인다")
		void excludesFromTotal() {
			Long userId = newUser();
			buy(userId, SAMSUNG, 10, 70_000);
			buy(userId, HYNIX, 1, 10_000);
			givenPrices(Map.of(HYNIX, 300_000L));

			PortfolioRes res = portfolioQueryService.list(userId, PortfolioSort.EVALUATION);

			assertThat(res.evaluationAmount()).isEqualTo(300_000);
		}

		@Test
		@DisplayName("정렬에서 뒤로 밀린다 — 두 정렬 모두")
		void sortsLast() {
			Long userId = newUser();
			buy(userId, SAMSUNG, 10, 70_000);
			buy(userId, HYNIX, 1, 10_000);
			givenPrices(Map.of(HYNIX, 300_000L));

			assertThat(codes(userId, PortfolioSort.EVALUATION)).containsExactly(HYNIX, SAMSUNG);
			assertThat(codes(userId, PortfolioSort.PROFIT_RATE)).containsExactly(HYNIX, SAMSUNG);
		}
	}

	@Nested
	@DisplayName("asOf")
	class AsOf {

		@Test
		@DisplayName("보유 종목들의 시세 기준 시각 중 가장 오래된 값이다 — 화면이 실제보다 신선하다고 오해하지 않게")
		void takesOldest() {
			Long userId = newUser();
			buy(userId, SAMSUNG, 1, 10_000);
			buy(userId, HYNIX, 1, 10_000);
			Instant fresh = Instant.now().truncatedTo(ChronoUnit.MILLIS);
			Instant old = fresh.minusSeconds(120);
			givenSnapshots(Map.of(
				SAMSUNG, new PriceSnapshot(100_000L, 0L, BigDecimal.ZERO, fresh, false),
				HYNIX, new PriceSnapshot(100_000L, 0L, BigDecimal.ZERO, old, true)));

			PortfolioRes res = portfolioQueryService.list(userId, PortfolioSort.EVALUATION);

			assertThat(res.asOf().toInstant()).isEqualTo(old);
		}

		@Test
		@DisplayName("시세가 있는 종목만 후보다 — 값 없는 종목의 null 이 기준 시각을 지우지 않는다")
		void ignoresMissing() {
			Long userId = newUser();
			buy(userId, SAMSUNG, 1, 10_000);
			buy(userId, HYNIX, 1, 10_000);
			Instant asOf = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60);
			givenSnapshots(Map.of(SAMSUNG, new PriceSnapshot(100_000L, 0L, BigDecimal.ZERO, asOf, false)));

			assertThat(portfolioQueryService.list(userId, PortfolioSort.EVALUATION).asOf().toInstant()).isEqualTo(asOf);
		}
	}

	@Nested
	@DisplayName("계좌 요약")
	class AccountSummary {

		@Test
		@DisplayName("GET /account 의 평가금액이 이제 실제 값이다 — 목록 합계와 같은 엔진을 지난다")
		void reportsRealValuation() {
			Long userId = newUser();
			buy(userId, SAMSUNG, 10, 70_000);
			givenPrices(Map.of(SAMSUNG, 80_000L));

			AccountRes summary = accountService.getSummary(userId);
			PortfolioRes portfolio = portfolioQueryService.list(userId, PortfolioSort.EVALUATION);

			assertThat(summary.evaluationAmount()).isEqualTo(800_000);
			assertThat(summary.evaluationAmount()).isEqualTo(portfolio.evaluationAmount());
			assertThat(summary.totalAsset()).isEqualTo(portfolio.totalAsset());
		}
	}

	// ---- helpers ----

	/** 목이 요청한 모든 코드를 키로 돌려주게 한다 — 포트 계약이 그렇고, 서비스가 그 계약에 기대고 있다. */
	private void givenPrices(Map<String, Long> currentPrices) {
		Map<String, PriceSnapshot> snapshots = new LinkedHashMap<>();
		currentPrices.forEach((code, price) ->
			snapshots.put(code, new PriceSnapshot(price, 0L, BigDecimal.ZERO, Instant.now(), false)));
		givenSnapshots(snapshots);
	}

	private void givenSnapshots(Map<String, PriceSnapshot> snapshots) {
		given(priceQueryPort.latestAll(any())).willAnswer(invocation -> {
			Map<String, PriceSnapshot> result = new LinkedHashMap<>();
			((Iterable<?>) invocation.getArgument(0)).forEach(code ->
				result.put((String) code, snapshots.getOrDefault(code, PriceSnapshot.missing())));
			return result;
		});
	}

	private List<String> codes(Long userId, PortfolioSort sort) {
		return portfolioQueryService.list(userId, sort).holdings().stream()
			.map(PortfolioRes.Holding::stockCode)
			.toList();
	}

	private void buy(Long userId, String stockCode, long quantity, long price) {
		Long accountId = accountService.getBalance(userId).accountId();
		transactionTemplate.executeWithoutResult(status ->
			holdingCommandService.applyBuy(accountId, stockCode, quantity, price));
	}

	private void sell(Long userId, String stockCode, long quantity, long price) {
		Long accountId = accountService.getBalance(userId).accountId();
		transactionTemplate.executeWithoutResult(status ->
			holdingCommandService.applySell(accountId, stockCode, quantity, price));
	}

	private Long newUser() {
		Long userId = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg"))
			.getId();
		accountService.ensureAccount(userId);
		return userId;
	}
}
