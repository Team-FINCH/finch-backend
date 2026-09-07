package com.finch.domain.stock.service;

import com.finch.domain.stock.dto.request.CandlePeriod;
import com.finch.domain.stock.dto.response.CandleRes;
import com.finch.domain.stock.dto.response.StockDetailRes;
import com.finch.domain.stock.dto.response.StockPriceRes;
import com.finch.domain.stock.dto.response.StockSearchRes;
import com.finch.domain.stock.dto.response.TradabilityRes;
import com.finch.domain.stock.entity.DailyCandle;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.event.StockSearchedEvent;
import com.finch.domain.stock.event.StockViewedEvent;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.stock.port.HoldingQueryPort;
import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.domain.stock.port.WatchlistQueryPort;
import com.finch.domain.stock.repository.DailyCandleRepository;
import com.finch.domain.stock.repository.StockRepository;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.exception.CustomException;
import com.finch.global.util.KstTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종목 검색·상세·캔들 (apiSpec §5.1~§5.3, featureSpec 4·7.1·7.2). 종목 마스터는 이 도메인 소유이고 <b>상위 도메인 데이터는
 * 포트로 받는다</b> — 시세({@link PriceQueryPort})·보유({@link HoldingQueryPort})·관심({@link WatchlistQueryPort}).
 * stock(1층)이 price·portfolio·watchlist 를 직접 부르면 규칙 2 위반이다. 지금은 기본 빈이 빈 값을 준다.
 * <p>
 * "봤다"·"검색했다"는 이벤트로만 알린다 — recent(4층)를 직접 부르는 것도 역방향이다. 상세는 응답을 기다리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StockService {

	private final StockRepository stockRepository;
	private final DailyCandleRepository dailyCandleRepository;
	private final PriceQueryPort priceQueryPort;
	private final HoldingQueryPort holdingQueryPort;
	private final WatchlistQueryPort watchlistQueryPort;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 검색 (apiSpec 5.1). 컨트롤러가 길이·범위를 검증했지만 앞뒤 공백을 걷어낸 뒤 다시 본다 — {@code " 삼"} 은 2글자 검증을
	 * 통과하고도 검색어는 1글자다. 시세는 {@code latestAll} 로 한 번에 붙인다 (N+1 금지).
	 * 검색 이벤트는 검증을 통과한 검색어만 발행한다.
	 */
	@Transactional(readOnly = true)
	public StockSearchRes search(Long userId, String keyword, int size) {
		String trimmed = keyword == null ? "" : keyword.strip();
		if (trimmed.length() < 2) {
			throw new CustomException(GeneralErrorCode.INVALID_REQUEST, Map.of("keyword", "2글자 이상 입력해 주세요"));
		}
		List<Stock> stocks = stockRepository.searchByKeyword(trimmed, size);
		Map<String, PriceSnapshot> prices = priceQueryPort.latestAll(stocks.stream().map(Stock::getStockCode).toList());
		eventPublisher.publishEvent(new StockSearchedEvent(userId, trimmed));
		return new StockSearchRes(stocks.stream()
			.map(stock -> StockSearchRes.Item.of(stock, prices.getOrDefault(stock.getStockCode(), PriceSnapshot.missing())))
			.toList());
	}

	/**
	 * 상세 (apiSpec 5.2). 상장폐지 종목은 404 다 — 검색에서 빠지는 종목이 상세에서만 보이면 화면이 갈 곳이 없고, 보유 중
	 * 상장폐지는 MVP 에서 생기지 않는다 (contracts C78). 이 호출이 곧 "최근 본 종목" 기록이다 — 이벤트로 알린다.
	 */
	@Transactional(readOnly = true)
	public StockDetailRes detail(Long userId, String stockCode) {
		Stock stock = findActive(stockCode);
		PriceSnapshot price = priceQueryPort.latest(stockCode);
		boolean watched = watchlistQueryPort.isWatched(userId, stockCode);
		StockDetailRes res = StockDetailRes.of(stock, price, watched, holdingQueryPort.holdingOf(userId, stockCode));
		eventPublisher.publishEvent(new StockViewedEvent(userId, stockCode, Instant.now()));
		return res;
	}

	/**
	 * 캔들 (apiSpec 5.3). 오늘(KST)부터 {@code period.days()} 달력일 전까지의 일봉. 봉이 없으면 빈 배열 — 에러가 아니다.
	 * 상장폐지 종목은 상세와 같은 이유로 404.
	 */
	@Transactional(readOnly = true)
	public CandleRes candles(String stockCode, CandlePeriod period) {
		findActive(stockCode);
		LocalDate to = LocalDate.now(KstTime.ZONE);
		LocalDate from = to.minusDays(period.days());
		List<DailyCandle> candles = dailyCandleRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(stockCode, from,
			to);
		return CandleRes.of(stockCode, period, candles);
	}

	/**
	 * 현재가 단건 (apiSpec 5.4). <b>이 엔드포인트를 stock 이 소유한다</b> — 없는 종목에 {@code STOCK_NOT_FOUND} 를 내야 하는데
	 * 그 판정은 stock 만 할 수 있고, price(1층)가 stock(1층)을 부르는 것은 같은 층 참조다. 존재를 확인한 뒤 시세는 포트로 받는다.
	 * <p>
	 * 다건 조회({@code GET /stocks/prices})는 존재 판정이 없어 price 도메인이 소유한다 — 캐시에 없으면 "값 없음" 이면 그만이다.
	 */
	@Transactional(readOnly = true)
	public StockPriceRes price(String stockCode) {
		findActive(stockCode);
		return StockPriceRes.of(stockCode, priceQueryPort.latest(stockCode));
	}

	/** 주문(S9)이 부른다. 없는 종목·상장폐지는 {@code exists=false}, 거래정지는 사유와 함께. 예외를 던지지 않는다 — 판정은 주문의 몫이다. */
	@Transactional(readOnly = true)
	public TradabilityRes getTradable(String stockCode) {
		return stockRepository.findByStockCodeAndIsActiveTrue(stockCode)
			.map(s -> new TradabilityRes(true, s.isSuspended(), s.getSuspendedReason()))
			.orElseGet(TradabilityRes::missing);
	}

	private Stock findActive(String stockCode) {
		return stockRepository.findByStockCodeAndIsActiveTrue(stockCode)
			.orElseThrow(() -> new CustomException(StockErrorCode.STOCK_NOT_FOUND));
	}
}
