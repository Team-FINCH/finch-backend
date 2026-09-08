package com.finch.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.stock.dto.request.CandleInterval;
import com.finch.domain.stock.dto.request.CandlePeriod;
import com.finch.domain.stock.dto.response.CandleRes;
import com.finch.domain.stock.entity.DailyCandle;
import com.finch.domain.stock.entity.Market;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.port.CandleSourcePort;
import com.finch.domain.stock.port.CandleSourcePort.CandleData;
import com.finch.domain.stock.repository.DailyCandleRepository;
import com.finch.domain.stock.repository.StockRepository;
import com.finch.global.util.KstTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 일봉 lazy 적재와 일일 갱신. 원천({@link CandleSourcePort})은 목이고 DB 는 진짜다 — "두 번째 요청은 원천을 부르지 않는다" 와
 * "이미 있는 날은 건너뛴다" 는 저장이 실제로 됐을 때만 성립한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CandleSyncServiceTest {

	private static final AtomicLong CODE_SEQ = new AtomicLong(0);

	@Autowired
	private StockService stockService;

	@Autowired
	private CandleSyncService candleSyncService;

	@Autowired
	private DailyCandleRepository dailyCandleRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private CandleSourcePort candleSource;

	/** 받아 오는 기간은 {@code finch.stock.candle.backfill-days} 다. 월봉(period=3Y) 때문에 1년에서 3년으로 늘렸다 (이슈 #37). */
	@Test
	@DisplayName("봉이 없는 종목의 캔들 요청은 원천에서 3년치를 받아 넣고, 두 번째 요청은 원천을 부르지 않는다")
	void lazyBackfillOnce() {
		String code = newStock(10_000L);
		LocalDate today = LocalDate.now(KstTime.ZONE);
		given(candleSource.dailyCandles(eq(code), any(), any())).willReturn(List.of(
			new CandleData(today.minusDays(2), 100, 110, 90, 105, 7),
			new CandleData(today.minusDays(1), 105, 115, 95, 108, 8)));

		CandleRes first = stockService.candles(code, CandlePeriod.ONE_MONTH, CandleInterval.DAY);
		CandleRes second = stockService.candles(code, CandlePeriod.ONE_MONTH, CandleInterval.DAY);

		assertThat(first.candles()).hasSize(2);
		assertThat(second.candles()).hasSize(2);
		// to 는 어제다. 장중에 부르면 KIS 가 진행 중인 오늘 봉을 주는데, 저장하면 영영 미완성으로 굳는다.
		LocalDate yesterday = today.minusDays(1);
		verify(candleSource, times(1)).dailyCandles(eq(code), eq(yesterday.minusDays(1095)), eq(yesterday));
		// 마지막 봉 종가로 기준가를 맞춘다.
		assertThat(stockRepository.findById(code).orElseThrow().getPreviousClose()).isEqualTo(108L);
	}

	/**
	 * 장중에 백필이 돌면 원천이 진행 중인 오늘 봉을 함께 줄 수 있다. 그것을 저장하면 다시 고칠 경로가 없다 —
	 * 백필은 봉이 있으면 안 돌고, 일일 갱신은 마지막 봉 다음 날부터 받고, save 는 이미 있는 날을 거른다.
	 * 그래서 요청 자체가 어제까지여야 한다.
	 */
	@Test
	@DisplayName("원천이 오늘 봉을 섞어 줘도 저장하지 않는다 — 확정된 봉만 넣는다")
	void doesNotStoreTodaysUnfinishedCandle() {
		String code = newStock(10_000L);
		LocalDate today = LocalDate.now(KstTime.ZONE);
		given(candleSource.dailyCandles(eq(code), any(), any())).willReturn(List.of(
			new CandleData(today.minusDays(1), 105, 115, 95, 108, 8),
			new CandleData(today, 108, 120, 100, 119, 3)));

		stockService.candles(code, CandlePeriod.ONE_MONTH, CandleInterval.DAY);

		assertThat(dailyCandleRepository.findLatestTradeDate(code)).contains(today.minusDays(1));
		// 기준가도 어제 종가여야 한다. 오늘 미완성 봉으로 맞추면 그 종목의 등락률이 계속 틀린다.
		assertThat(stockRepository.findById(code).orElseThrow().getPreviousClose()).isEqualTo(108L);
	}

	@Test
	@DisplayName("원천이 비어 있으면(fake 모드) 아무것도 넣지 않고 빈 차트다 — 에러가 아니다")
	void emptySourceMeansEmptyChart() {
		String code = newStock(10_000L);
		given(candleSource.dailyCandles(any(), any(), any())).willReturn(List.of());

		assertThat(stockService.candles(code, CandlePeriod.ONE_MONTH, CandleInterval.DAY).candles()).isEmpty();
		assertThat(candleSyncService.hasCandles(code)).isFalse();
	}

	@Test
	@DisplayName("원천 장애는 차트 요청을 실패시키지 않는다 — 빈 차트이고 다음 요청이 다시 시도한다")
	void sourceFailureIsSwallowed() {
		String code = newStock(10_000L);
		given(candleSource.dailyCandles(any(), any(), any())).willThrow(new IllegalStateException("KIS down"));

		assertThat(stockService.candles(code, CandlePeriod.ONE_MONTH, CandleInterval.DAY).candles()).isEmpty();
		verify(candleSource, times(1)).dailyCandles(eq(code), any(), any());
	}

	@Test
	@DisplayName("일일 갱신은 봉이 있는 종목만, 마지막 봉 다음 날부터 오늘까지 받고, 이미 있는 날은 건너뛴다")
	void dailyRefreshAppendsMissingOnly() {
		String code = newStock(10_000L);
		LocalDate today = LocalDate.now(KstTime.ZONE);
		transactionTemplate.executeWithoutResult(s -> dailyCandleRepository.saveAll(List.of(
			DailyCandle.of(code, today.minusDays(3), 1, 3, 1, 1, 1),
			DailyCandle.of(code, today.minusDays(2), 1, 3, 1, 2, 1))));
		given(candleSource.dailyCandles(eq(code), eq(today.minusDays(1)), eq(today))).willReturn(List.of(
			new CandleData(today.minusDays(2), 9, 9, 9, 9, 9), // 이미 있다 — 건너뛴다
			new CandleData(today.minusDays(1), 100, 110, 90, 105, 7),
			new CandleData(today, 105, 125, 95, 120, 8)));
		int added = candleSyncService.refreshDailyNow();

		assertThat(added).isEqualTo(2);
		List<DailyCandle> candles = dailyCandleRepository
			.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(code, today.minusDays(10), today);
		assertThat(candles).extracting(DailyCandle::getTradeDate)
			.containsExactly(today.minusDays(3), today.minusDays(2), today.minusDays(1), today);
		// 건너뛴 날은 기존 값 그대로다.
		assertThat(candles.get(1).getClosePrice()).isEqualTo(2);
		assertThat(stockRepository.findById(code).orElseThrow().getPreviousClose()).isEqualTo(120L);
	}

	private String newStock(long previousClose) {
		String code = "ZC" + String.format("%04d", CODE_SEQ.incrementAndGet());
		transactionTemplate.executeWithoutResult(s -> stockRepository.save(
			Stock.of(code, "일봉테스트", Market.KOSDAQ, false, null, previousClose, Instant.now())));
		return code;
	}
}
