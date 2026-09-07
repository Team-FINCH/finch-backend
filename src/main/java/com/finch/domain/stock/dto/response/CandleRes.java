package com.finch.domain.stock.dto.response;

import com.finch.domain.stock.dto.request.CandlePeriod;
import com.finch.domain.stock.entity.DailyCandle;
import java.time.LocalDate;
import java.util.List;

/**
 * `GET /stocks/{stockCode}/candles` 응답 (apiSpec 5.3). {@code interval} 은 언제나 {@code DAY} — 분봉은 확장 범위(S0-4).
 * {@code candles} 는 오래된 날이 먼저다.
 */
public record CandleRes(String stockCode, String period, String interval, List<Candle> candles) {

	public static CandleRes of(String stockCode, CandlePeriod period, List<DailyCandle> candles) {
		return new CandleRes(stockCode, period.value(), "DAY", candles.stream().map(Candle::from).toList());
	}

	public record Candle(LocalDate date, long open, long high, long low, long close, long volume) {

		public static Candle from(DailyCandle c) {
			return new Candle(c.getTradeDate(), c.getOpenPrice(), c.getHighPrice(), c.getLowPrice(), c.getClosePrice(),
				c.getVolume());
		}
	}
}
