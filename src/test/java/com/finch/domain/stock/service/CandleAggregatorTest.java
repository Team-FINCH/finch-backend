package com.finch.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.domain.stock.dto.request.CandleInterval;
import com.finch.domain.stock.dto.response.CandleRes;
import com.finch.domain.stock.entity.DailyCandle;
import com.finch.domain.stock.port.PriceQueryPort;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 묶는 규칙과 <b>진행 중 당일 봉</b>을 얹는 규칙을 고정한다 (apiSpec 5.3). 스프링도 DB 도 없다 — 순수 계산이라
 * {@code StockServiceTest} 의 통합 경로와 따로 두는 편이 빠르고, 경계값을 마음대로 만들 수 있다.
 */
class CandleAggregatorTest {

	private static final String CODE = "005930";

	private static DailyCandle candle(String date, long open, long high, long low, long close, long volume) {
		return DailyCandle.of(CODE, LocalDate.parse(date), open, high, low, close, volume);
	}

	private static PriceQueryPort.SessionBar session(String date, long open, long high, long low, long close,
		long volume) {
		return new PriceQueryPort.SessionBar(LocalDate.parse(date), open, high, low, close, volume);
	}

	@Nested
	@DisplayName("확정된 봉만 묶을 때")
	class StoredOnly {

		@Test
		@DisplayName("WEEK 는 시가는 첫날, 종가는 마지막날, 고가·저가는 구간 최대·최소, 거래량은 합이다")
		void weeklyFollowsOhlcv() {
			// 2026-09-07(월) ~ 09-09(수) 는 같은 주다.
			List<DailyCandle> dailies = List.of(
				candle("2026-09-07", 100, 120, 90, 110, 10),
				candle("2026-09-08", 110, 130, 105, 115, 20),
				candle("2026-09-09", 115, 118, 80, 95, 30));

			List<CandleRes.Candle> bars = CandleAggregator.aggregate(dailies, CandleInterval.WEEK);

			assertThat(bars).hasSize(1);
			CandleRes.Candle w = bars.getFirst();
			assertThat(w.date()).isEqualTo(LocalDate.parse("2026-09-07"));
			assertThat(w.open()).isEqualTo(100);
			assertThat(w.high()).isEqualTo(130);
			assertThat(w.low()).isEqualTo(80);
			assertThat(w.close()).isEqualTo(95);
			assertThat(w.volume()).isEqualTo(60);
		}

		@Test
		@DisplayName("주가 바뀌면 봉이 나뉘고, 거래일 없는 주는 봉을 만들지 않는다")
		void splitsOnWeekBoundaryAndSkipsEmptyWeeks() {
			// 09-04(금) / 09-07(월) 은 다른 주다. 그 사이 주말은 거래일이 없다.
			List<DailyCandle> dailies = List.of(
				candle("2026-09-04", 100, 105, 95, 102, 10),
				candle("2026-09-07", 102, 108, 100, 106, 20));

			List<CandleRes.Candle> bars = CandleAggregator.aggregate(dailies, CandleInterval.WEEK);

			assertThat(bars).hasSize(2);
			assertThat(bars).extracting(CandleRes.Candle::date)
				.containsExactly(LocalDate.parse("2026-09-04"), LocalDate.parse("2026-09-07"));
		}

		@Test
		@DisplayName("주봉의 date 는 그 주 월요일이 아니라 실제 첫 거래일이다")
		void weeklyDateIsFirstTradingDay() {
			// 2026-09-08(화)부터 시작 — 월요일이 휴장이었다고 본다.
			List<DailyCandle> dailies = List.of(
				candle("2026-09-08", 100, 110, 95, 105, 10),
				candle("2026-09-09", 105, 115, 100, 112, 20));

			List<CandleRes.Candle> bars = CandleAggregator.aggregate(dailies, CandleInterval.WEEK);

			assertThat(bars).hasSize(1);
			assertThat(bars.getFirst().date()).isEqualTo(LocalDate.parse("2026-09-08"));
		}
	}

	@Nested
	@DisplayName("진행 중 당일 봉을 얹을 때")
	class WithSessionBar {

		@Test
		@DisplayName("DAY 는 새 봉으로 붙는다")
		void dayAppendsNewBar() {
			List<DailyCandle> dailies = List.of(candle("2026-09-07", 100, 110, 95, 105, 10));

			List<CandleRes.Candle> bars = CandleAggregator.aggregate(dailies, CandleInterval.DAY,
				session("2026-09-08", 105, 118, 103, 116, 7));

			assertThat(bars).hasSize(2);
			CandleRes.Candle live = bars.getLast();
			assertThat(live.date()).isEqualTo(LocalDate.parse("2026-09-08"));
			assertThat(live.open()).isEqualTo(105);
			assertThat(live.high()).isEqualTo(118);
			assertThat(live.low()).isEqualTo(103);
			assertThat(live.close()).isEqualTo(116);
			assertThat(live.volume()).isEqualTo(7);
		}

		@Test
		@DisplayName("WEEK 는 같은 주면 마지막 봉에 합쳐진다 — 시가는 그대로, 종가는 현재가, 고저는 다시 비교, 거래량은 합")
		void weekMergesIntoLastBar() {
			// 09-07(월)이 확정, 09-08(화)이 진행 중. 같은 주다.
			List<DailyCandle> dailies = List.of(candle("2026-09-07", 100, 110, 95, 105, 10));

			List<CandleRes.Candle> bars = CandleAggregator.aggregate(dailies, CandleInterval.WEEK,
				session("2026-09-08", 105, 130, 80, 116, 7));

			assertThat(bars).hasSize(1);
			CandleRes.Candle w = bars.getFirst();
			assertThat(w.date()).isEqualTo(LocalDate.parse("2026-09-07"));
			assertThat(w.open()).isEqualTo(100);
			assertThat(w.high()).isEqualTo(130);
			assertThat(w.low()).isEqualTo(80);
			assertThat(w.close()).isEqualTo(116);
			assertThat(w.volume()).isEqualTo(17);
		}

		@Test
		@DisplayName("WEEK 라도 주가 바뀌었으면 새 봉이다")
		void weekAppendsWhenBucketChanges() {
			// 09-04(금) 확정, 09-07(월) 진행 중 — 다른 주다.
			List<DailyCandle> dailies = List.of(candle("2026-09-04", 100, 110, 95, 105, 10));

			List<CandleRes.Candle> bars = CandleAggregator.aggregate(dailies, CandleInterval.WEEK,
				session("2026-09-07", 105, 118, 103, 116, 7));

			assertThat(bars).hasSize(2);
			assertThat(bars.getLast().date()).isEqualTo(LocalDate.parse("2026-09-07"));
		}

		@Test
		@DisplayName("MONTH 는 같은 달이면 합쳐지고 달이 바뀌면 새 봉이다")
		void monthMergesOrAppends() {
			List<DailyCandle> sameMonth = List.of(candle("2026-09-01", 100, 110, 95, 105, 10));
			assertThat(CandleAggregator.aggregate(sameMonth, CandleInterval.MONTH,
				session("2026-09-30", 105, 140, 90, 130, 7))).hasSize(1);

			List<DailyCandle> prevMonth = List.of(candle("2026-08-31", 100, 110, 95, 105, 10));
			assertThat(CandleAggregator.aggregate(prevMonth, CandleInterval.MONTH,
				session("2026-09-01", 105, 118, 103, 116, 7))).hasSize(2);
		}

		@Test
		@DisplayName("배치가 그날을 이미 저장했으면 얹지 않는다 — 같은 날은 두 번 그리지 않는다")
		void ignoredWhenAlreadyStored() {
			List<DailyCandle> dailies = List.of(
				candle("2026-09-07", 100, 110, 95, 105, 10),
				candle("2026-09-08", 105, 120, 100, 118, 20));

			List<CandleRes.Candle> bars = CandleAggregator.aggregate(dailies, CandleInterval.DAY,
				session("2026-09-08", 105, 999, 1, 500, 7));

			assertThat(bars).hasSize(2);
			CandleRes.Candle last = bars.getLast();
			assertThat(last.high()).isEqualTo(120);
			assertThat(last.close()).isEqualTo(118);
		}

		@Test
		@DisplayName("세션 날짜가 마지막 확정 봉보다 앞이면 무시한다 — 옛 캐시 값이다")
		void ignoredWhenOlderThanStored() {
			List<DailyCandle> dailies = List.of(candle("2026-09-08", 105, 120, 100, 118, 20));

			List<CandleRes.Candle> bars = CandleAggregator.aggregate(dailies, CandleInterval.DAY,
				session("2026-09-07", 1, 2, 1, 2, 1));

			assertThat(bars).hasSize(1);
			assertThat(bars.getFirst().date()).isEqualTo(LocalDate.parse("2026-09-08"));
		}

		@Test
		@DisplayName("확정된 봉이 하나도 없으면 진행 중 봉만 그린다")
		void onlySessionBarWhenNothingStored() {
			List<CandleRes.Candle> bars = CandleAggregator.aggregate(List.of(), CandleInterval.WEEK,
				session("2026-09-08", 105, 118, 103, 116, 7));

			assertThat(bars).hasSize(1);
			assertThat(bars.getFirst().close()).isEqualTo(116);
		}

		@Test
		@DisplayName("세션 봉이 없으면 확정된 봉 그대로다")
		void nullSessionChangesNothing() {
			List<DailyCandle> dailies = List.of(candle("2026-09-07", 100, 110, 95, 105, 10));

			assertThat(CandleAggregator.aggregate(dailies, CandleInterval.DAY, null))
				.isEqualTo(CandleAggregator.aggregate(dailies, CandleInterval.DAY));
		}
	}
}
