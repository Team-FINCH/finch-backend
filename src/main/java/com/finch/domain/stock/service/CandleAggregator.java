package com.finch.domain.stock.service;

import com.finch.domain.stock.dto.request.CandleInterval;
import com.finch.domain.stock.dto.response.CandleRes;
import com.finch.domain.stock.entity.DailyCandle;
import com.finch.domain.stock.port.PriceQueryPort;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 일봉을 주봉·월봉으로 묶는다 (apiSpec 5.3). <b>읽을 때만 묶는다</b> — 저장은 {@code daily_candle} 하나이고
 * 마이그레이션이 없다 (erd.md §2.8).
 * <p>
 * 규칙은 봉의 정의 그대로다. 시가는 <b>구간 첫 거래일의 시가</b>, 종가는 <b>마지막 거래일의 종가</b>, 고가·저가는 구간의
 * 최대·최소, 거래량은 합이다. 응답의 {@code date} 는 구간의 첫 거래일이다 — 그 주 월요일이 휴장이면 월요일이 아니라
 * 실제 거래가 있었던 첫날이다.
 * <p>
 * <b>없는 구간은 만들지 않는다.</b> 거래일이 하나도 없는 주·달은 봉 자체가 없다. 빈 봉(거래량 0)을 끼워 넣으면 차트에
 * 실제로 없던 가격이 그려지고, 프론트가 그것을 걸러 낼 방법이 없다.
 * <p>
 * <b>마지막 봉은 진행 중일 수 있다.</b> 이번 주·이번 달이 안 끝났으면 그 시점까지의 값이다 — 일봉의 마지막 봉이 장중에
 * 움직이는 것과 같아서 따로 표시하지 않는다.
 * <p>
 * {@code DAY} 도 같은 경로를 탄다. 하루가 곧 한 구간이라 결과가 입력과 1:1 이고, 분기를 두지 않아 검증할 길이 하나다.
 */
final class CandleAggregator {

	private CandleAggregator() {
	}

	/**
	 * 확정된 봉 뒤에 <b>진행 중인 당일 봉</b>을 얹는다 (apiSpec 5.3).
	 * <p>
	 * 얹는 조건이 하나다 — {@code session} 의 날짜가 <b>마지막 확정 봉보다 뒤</b>여야 한다. 같거나 앞이면 16:00 배치가
	 * 이미 그날을 저장한 것이라 두 번 그리게 된다. 이 판정 덕분에 배치가 도는 순간 얹는 동작이 저절로 멈춘다.
	 * <p>
	 * <b>{@code DAY} 는 새 봉으로 붙고, {@code WEEK}·{@code MONTH} 는 마지막 봉에 합쳐진다.</b> 오늘이 이번 주에 속하므로
	 * 주봉을 하나 더 만들면 같은 주가 둘이 된다. 합칠 때는 고가·저가를 다시 비교하고 종가를 현재가로 바꾸고 거래량을 더한다 —
	 * 시가는 그 주의 첫 거래일 것이라 그대로 둔다.
	 */
	static List<CandleRes.Candle> aggregate(List<DailyCandle> dailies, CandleInterval interval,
		PriceQueryPort.SessionBar session) {
		List<CandleRes.Candle> bars = aggregate(dailies, interval);
		if (session == null) {
			return bars;
		}
		LocalDate lastStored = dailies.isEmpty() ? null : dailies.getLast().getTradeDate();
		if (lastStored != null && !session.date().isAfter(lastStored)) {
			return bars;
		}

		CandleRes.Candle live = new CandleRes.Candle(session.date(), session.open(), session.high(), session.low(),
			session.close(), session.volume());
		if (bars.isEmpty()) {
			return List.of(live);
		}

		CandleRes.Candle tail = bars.getLast();
		if (!interval.bucketOf(session.date()).equals(interval.bucketOf(tail.date()))) {
			List<CandleRes.Candle> out = new ArrayList<>(bars);
			out.add(live);
			return out;
		}

		List<CandleRes.Candle> out = new ArrayList<>(bars.subList(0, bars.size() - 1));
		out.add(new CandleRes.Candle(tail.date(), tail.open(), Math.max(tail.high(), session.high()),
			Math.min(tail.low(), session.low()), session.close(), tail.volume() + session.volume()));
		return out;
	}

	/**
	 * @param dailies <b>날짜 오름차순</b>이어야 한다. 한 번만 훑으며 구간이 바뀌는 지점에서 끊으므로 정렬이 곧 전제다
	 *                ({@code findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc}).
	 * @return 오래된 구간이 먼저. 입력이 비면 빈 목록이다 — 봉이 없는 종목은 에러가 아니다 (apiSpec 5.3).
	 */
	static List<CandleRes.Candle> aggregate(List<DailyCandle> dailies, CandleInterval interval) {
		List<CandleRes.Candle> out = new ArrayList<>();
		LocalDate bucket = null;
		LocalDate date = null;
		long open = 0;
		long high = 0;
		long low = 0;
		long close = 0;
		long volume = 0;

		for (DailyCandle c : dailies) {
			LocalDate key = interval.bucketOf(c.getTradeDate());
			if (!key.equals(bucket)) {
				if (bucket != null) {
					out.add(new CandleRes.Candle(date, open, high, low, close, volume));
				}
				bucket = key;
				date = c.getTradeDate();
				open = c.getOpenPrice();
				high = c.getHighPrice();
				low = c.getLowPrice();
				close = c.getClosePrice();
				volume = c.getVolume();
			} else {
				high = Math.max(high, c.getHighPrice());
				low = Math.min(low, c.getLowPrice());
				close = c.getClosePrice();
				volume += c.getVolume();
			}
		}
		if (bucket != null) {
			out.add(new CandleRes.Candle(date, open, high, low, close, volume));
		}
		return out;
	}
}
