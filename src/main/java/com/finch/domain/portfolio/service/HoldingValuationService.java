package com.finch.domain.portfolio.service;

import com.finch.domain.portfolio.dto.response.PortfolioRes;
import com.finch.domain.portfolio.repository.HoldingRepository;
import com.finch.domain.portfolio.repository.HoldingRow;
import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보유 종목에 시세를 붙여 평가하는 엔진. <b>보유 → 시세 → 평가</b> 파이프라인이 여기 한 번만 있다.
 * <p>
 * 이 값을 쓰는 곳이 셋인데 들어오는 문이 다르다 — 보유 목록({@code GET /portfolio}), 계좌 요약의 평가금액
 * ({@link com.finch.domain.account.port.ValuationPort}), 종목 상세의 보유 카드
 * ({@link com.finch.domain.stock.port.HoldingQueryPort}). 세 문이 각자 조회하면 시세를 붙이는 방법과
 * {@code asOf} 를 고르는 기준이 갈린다.
 * <p>
 * <b>왜 별도 빈인가.</b> {@code PortfolioQueryService} 가 {@code ValuationPort} 를 직접 구현하면 빈 순환이 생긴다 —
 * {@code AccountService} → {@code ValuationPort} → 그 구현체 → (예수금이 필요하니) {@code AccountService}.
 * 스프링이 {@code BeanCurrentlyInCreationException} 으로 기동에 실패한다. 그래서 <b>포트 구현에서 account 의존을
 * 걷어내고</b> 그 조각을 이 클래스로 내렸다. 이 클래스는 {@code accountId} 를 받기만 하고 계좌를 읽지 않는다.
 */
@Service
public class HoldingValuationService {

	private final HoldingRepository holdingRepository;
	private final PriceQueryPort priceQueryPort;
	private final Clock clock;

	@Autowired
	public HoldingValuationService(HoldingRepository holdingRepository, PriceQueryPort priceQueryPort) {
		this(holdingRepository, priceQueryPort, Clock.systemUTC());
	}

	/**
	 * {@code asOf} 를 확인하려는 테스트가 쓴다. 생성자가 둘이면 스프링이 어느 쪽으로 주입할지 몰라
	 * {@code NoSuchMethodException} 으로 기동이 깨지므로 위쪽에 {@code @Autowired} 를 붙였다 ({@code MarketClock} 참고).
	 */
	public HoldingValuationService(HoldingRepository holdingRepository, PriceQueryPort priceQueryPort, Clock clock) {
		this.holdingRepository = holdingRepository;
		this.priceQueryPort = priceQueryPort;
		this.clock = clock;
	}

	/**
	 * 계좌의 보유 종목 전부를 평가한다.
	 * <p>
	 * 시세는 {@code latestAll} <b>한 번</b>으로 받는다. 종목마다 묻지 않는 이유는 검색·관심 목록과 같다 — 보유 종목 수만큼
	 * Redis 왕복이 생긴다. 이 호출은 관심 신호도 겸한다 (apiSpec 5.6) — 잔고 화면을 열어 둔 동안 그 종목들의 시세가 계속 갱신된다.
	 */
	@Transactional(readOnly = true)
	public PricedHoldings evaluate(Long accountId) {
		List<HoldingRow> rows = holdingRepository.findHeld(accountId);
		if (rows.isEmpty()) {
			return new PricedHoldings(List.of(), 0L, Instant.now(clock));
		}

		Map<String, PriceSnapshot> prices = priceQueryPort.latestAll(rows.stream().map(HoldingRow::getStockCode).toList());

		List<PortfolioRes.Holding> holdings = new ArrayList<>(rows.size());
		long evaluationAmount = 0L;
		Instant asOf = null;
		for (HoldingRow row : rows) {
			PriceSnapshot price = prices.getOrDefault(row.getStockCode(), PriceSnapshot.missing());
			PortfolioRes.Holding holding = PortfolioRes.Holding.of(row, price.currentPrice());
			holdings.add(holding);

			if (holding.evaluationAmount() != null) {
				evaluationAmount += holding.evaluationAmount();
			}
			// 시세가 없는 종목은 asOf 후보가 아니다 — 그 종목의 asOf 는 null 이다.
			if (price.asOf() != null && (asOf == null || price.asOf().isBefore(asOf))) {
				asOf = price.asOf();
			}
		}
		return new PricedHoldings(holdings, evaluationAmount, asOf == null ? Instant.now(clock) : asOf);
	}

	/**
	 * 평가된 보유 목록.
	 *
	 * @param evaluationAmount Σ(수량 × 현재가). <b>시세가 없는 종목은 더하지 않는다</b> — 0 으로 치면 그 종목의 자산이 사라진 것처럼
	 *                         보이고, 빼면 합계가 실제보다 작아진다. 어느 쪽도 맞지 않지만 후자는 해당 종목의 평가 필드가 전부
	 *                         null 로 나가 화면에서 "값 없음"이 보이므로 사용자가 합계가 부분값임을 알 수 있다 (apiSpec 8.1).
	 * @param asOf             보유 종목들의 시세 기준 시각 중 <b>가장 오래된 값</b>. 보유가 없거나 전부 시세가 없으면 현재 시각이다 —
	 *                         평가할 것이 없으면 "지금 기준" 이 맞고, 화면의 갱신 시각이 비어 보이지 않는다.
	 */
	public record PricedHoldings(List<PortfolioRes.Holding> holdings, long evaluationAmount, Instant asOf) {

		/** 정렬은 이 단계에서 한다 — 기준 값이 시세에서 나와 DB 가 볼 수 없다. 값이 없는 종목은 뒤로 민다. */
		public List<PortfolioRes.Holding> sortedBy(Comparator<PortfolioRes.Holding> comparator) {
			List<PortfolioRes.Holding> sorted = new ArrayList<>(holdings);
			sorted.sort(comparator);
			return sorted;
		}
	}
}
