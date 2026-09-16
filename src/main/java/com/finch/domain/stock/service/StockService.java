package com.finch.domain.stock.service;

import com.finch.domain.stock.dto.request.CandleInterval;
import com.finch.domain.stock.dto.request.CandlePeriod;
import com.finch.domain.stock.dto.response.CandleRes;
import com.finch.domain.stock.dto.response.StockDetailRes;
import com.finch.domain.stock.dto.response.StockPriceRes;
import com.finch.domain.stock.dto.response.StockSearchRes;
import com.finch.domain.stock.dto.response.TradabilityRes;
import com.finch.domain.stock.entity.DailyCandle;
import com.finch.domain.stock.entity.Stock;
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
import com.finch.global.util.StockUniverse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종목 검색·상세·캔들 (apiSpec §5.1~§5.3, featureSpec 4·7.1·7.2). 종목 마스터는 이 도메인 소유이고 <b>상위 도메인 데이터는
 * 포트로 받는다</b> — 시세({@link PriceQueryPort})·보유({@link HoldingQueryPort})·관심({@link WatchlistQueryPort}).
 * stock(1층)이 price·portfolio·watchlist 를 직접 부르면 규칙 2 위반이다. 지금은 기본 빈이 빈 값을 준다.
 * <p>
 * "봤다"·"검색했다"는 이벤트로 알리지만 <b>발행은 여기가 아니라 {@link com.finch.domain.stock.controller.StockController}
 * 가 한다</b>. recent(4층)를 직접 부르지 않는다는 규칙은 그대로이고, 발행 위치만 트랜잭션 밖으로 나갔다 (이슈 309).
 * <p>
 * <b>왜 트랜잭션 밖인가</b> — 스프링 이벤트는 기본이 동기라 리스너가 발행 지점에서 그 자리에 실행된다. 이 메서드들이
 * {@code readOnly = true} 라 리스너는 INSERT 를 하려고 새 트랜잭션을 열어야 했고({@code REQUIRES_NEW}), 중단된 바깥
 * 트랜잭션은 커넥션을 쥔 채 남아 <b>요청 하나가 커넥션 2개를 동시에 점유</b>했다. 풀 크기만큼의 요청이 겹치면 서로의
 * 두 번째 커넥션을 기다리며 데드락이 됐다 (풀 4에서 동시 4건이면 재현).
 */
@Service
@RequiredArgsConstructor
public class StockService {

	private final StockRepository stockRepository;
	private final DailyCandleRepository dailyCandleRepository;
	private final PriceQueryPort priceQueryPort;
	private final HoldingQueryPort holdingQueryPort;
	private final WatchlistQueryPort watchlistQueryPort;
	private final CandleSyncService candleSyncService;
	private final StockUniverse universe;

	/**
	 * 검색 (apiSpec 5.1). 컨트롤러가 길이·범위를 검증했지만 앞뒤 공백을 걷어낸 뒤 다시 본다 — {@code " 삼"} 은 2글자 검증을
	 * 통과하고도 검색어는 1글자다. 시세는 {@code latestAll} 로 한 번에 붙인다 (N+1 금지).
	 * 검색어가 2글자 미만이면 여기서 던지므로 컨트롤러의 발행 줄에 닿지 않는다 — 검증을 통과한 검색어만 기록된다.
	 * 컨트롤러는 여기와 같은 규칙({@code strip()})으로 걷어낸 값을 싣는다.
	 * <p>
	 * 종목 범위({@link StockUniverse})가 켜져 있으면 범위 안에서만 찾는다 — 범위 밖 종목은 검색에 나타나지 않아야 상세로 갈 길이 없다.
	 */
	@Transactional(readOnly = true)
	public StockSearchRes search(Long userId, String keyword, int size) {
		String trimmed = keyword == null ? "" : keyword.strip();
		if (trimmed.length() < 2) {
			throw new CustomException(GeneralErrorCode.INVALID_REQUEST, Map.of("keyword", "2글자 이상 입력해 주세요"));
		}
		List<Stock> stocks = universe.restricted()
			? stockRepository.searchByKeywordWithin(trimmed, universe.codes(), size)
			: stockRepository.searchByKeyword(trimmed, size);
		Map<String, PriceSnapshot> prices = priceQueryPort.latestAll(stocks.stream().map(Stock::getStockCode).toList());
		return new StockSearchRes(stocks.stream()
			.map(stock -> StockSearchRes.Item.of(stock, prices.getOrDefault(stock.getStockCode(), PriceSnapshot.missing())))
			.toList());
	}

	/**
	 * 상세 (apiSpec 5.2). 상장폐지 종목은 404 다 — 검색에서 빠지는 종목이 상세에서만 보이면 화면이 갈 곳이 없고, 보유 중
	 * 상장폐지는 MVP 에서 생기지 않는다 (contracts C78). 이 호출이 곧 "최근 본 종목" 기록이지만, 기록을 여는
	 * {@code StockViewedEvent} 는 컨트롤러가 이 메서드가 끝난 뒤에 발행한다 — 여기서 발행하면 트랜잭션 안이 된다.
	 * 없는 종목이면 여기서 던지므로 그 발행 줄에 닿지 않는다.
	 */
	@Transactional(readOnly = true)
	public StockDetailRes detail(Long userId, String stockCode) {
		Stock stock = findActive(stockCode);
		PriceSnapshot price = priceQueryPort.latest(stockCode);
		boolean watched = watchlistQueryPort.isWatched(userId, stockCode);
		return StockDetailRes.of(stock, price, watched, holdingQueryPort.holdingOf(userId, stockCode));
	}

	/**
	 * 캔들 (apiSpec 5.3). 오늘(KST)부터 {@code period.days()} 달력일 전까지를 읽어 {@code interval} 단위로 묶는다.
	 * 봉이 없으면 빈 배열 — 에러가 아니다. 상장폐지 종목은 상세와 같은 이유로 404.
	 * <p>
	 * <b>읽는 것은 언제나 일봉이다.</b> 주봉·월봉은 {@link CandleAggregator} 가 그 자리에서 묶는다 — 저장은
	 * {@code daily_candle} 하나이고 마이그레이션이 없다 (erd.md §2.8). 묶는 규칙은 그 클래스 주석에 있다.
	 * <p>
	 * <b>마지막에 진행 중인 당일 봉을 얹는다</b> ({@link PriceQueryPort#sessionBar}). 확정 전의 봉이라 저장하지 않고
	 * 응답을 만들 때마다 시세 캐시에서 새로 읽는다 — 저장하면 미완성인 채로 굳는다
	 * ({@link CandleSyncService#backfillIfEmpty} 주석). 16:00 배치가 그날을 저장하면 날짜 비교에서 걸러져 저절로 멈춘다.
	 * <p>
	 * <b>이 메서드에는 {@code @Transactional} 이 없다.</b> 봉이 없는 종목이면 {@link CandleSyncService#backfillIfEmpty} 가 KIS 를 부르는데
	 * (S10 lazy 적재) 외부 HTTP 는 트랜잭션 밖이어야 한다. 읽기 둘은 각자 짧은 트랜잭션으로 충분하다 — 지연 로딩이 없다.
	 */
	public CandleRes candles(String stockCode, CandlePeriod period, CandleInterval interval) {
		findActive(stockCode);
		candleSyncService.backfillIfEmpty(stockCode);
		LocalDate to = LocalDate.now(KstTime.ZONE);
		LocalDate from = to.minusDays(period.days());
		List<DailyCandle> dailies = dailyCandleRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(stockCode, from,
			to);
		PriceQueryPort.SessionBar session = priceQueryPort.sessionBar(stockCode).orElse(null);
		return CandleRes.of(stockCode, period, interval, CandleAggregator.aggregate(dailies, interval, session));
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

	/**
	 * 주문(S9)·관심 등록(S6)이 부른다. 없는 종목·상장폐지·<b>종목 범위 밖</b>은 {@code exists=false}, 거래정지는 사유와 함께.
	 * 예외를 던지지 않는다 — 판정은 주문의 몫이다. 범위 밖을 "없음" 으로 보는 것은 {@link #findActive} 와 같은 규칙이다.
	 */
	@Transactional(readOnly = true)
	public TradabilityRes getTradable(String stockCode) {
		if (!universe.contains(stockCode)) {
			return TradabilityRes.missing();
		}
		return stockRepository.findByStockCodeAndIsActiveTrue(stockCode)
			.map(s -> new TradabilityRes(true, s.getStockName(), s.isSuspended(), s.getSuspendedReason()))
			.orElseGet(TradabilityRes::missing);
	}

	/**
	 * 활성 종목이고 <b>종목 범위 안</b>이어야 한다. 둘 다 {@code STOCK_NOT_FOUND} 다 — 범위 밖 종목은 서비스에 없는 종목이고,
	 * 코드를 따로 두면 프론트가 새 분기를 만들어야 한다. 검색에 나오지 않는 종목이라 사용자가 마주칠 일도 없다 (apiSpec §5 머리).
	 */
	private Stock findActive(String stockCode) {
		if (!universe.contains(stockCode)) {
			throw new CustomException(StockErrorCode.STOCK_NOT_FOUND);
		}
		return stockRepository.findByStockCodeAndIsActiveTrue(stockCode)
			.orElseThrow(() -> new CustomException(StockErrorCode.STOCK_NOT_FOUND));
	}
}
