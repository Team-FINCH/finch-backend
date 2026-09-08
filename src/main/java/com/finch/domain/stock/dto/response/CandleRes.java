package com.finch.domain.stock.dto.response;

import com.finch.domain.stock.dto.request.CandleInterval;
import com.finch.domain.stock.dto.request.CandlePeriod;
import java.time.LocalDate;
import java.util.List;

/**
 * `GET /stocks/{stockCode}/candles` 응답 (apiSpec 5.3). {@code candles} 는 오래된 것이 먼저다.
 * <p>
 * {@code period} 는 얼마나 거슬러 올라갔는지, {@code interval} 은 봉 하나가 얼마인지다. 두 축은 독립이다.
 * 봉을 묶는 것은 {@code CandleAggregator} 이고 이 레코드는 결과를 담기만 한다 — 응답 모양이 계약이라
 * 계산이 여기 들어가면 계약과 규칙이 한곳에서 섞인다.
 */
public record CandleRes(String stockCode, String period, String interval, List<Candle> candles) {

	public static CandleRes of(String stockCode, CandlePeriod period, CandleInterval interval, List<Candle> candles) {
		return new CandleRes(stockCode, period.value(), interval.value(), candles);
	}

	/**
	 * 봉 하나. {@code date} 는 그 구간의 <b>첫 거래일</b>이다 — 주봉이라고 그 주 월요일이 나가지 않는다.
	 * 월요일이 휴장이면 실제로 거래가 있었던 첫날이다.
	 */
	public record Candle(LocalDate date, long open, long high, long low, long close, long volume) {
	}
}
