package com.finch.domain.stock.port;

import java.time.LocalDate;
import java.util.List;

/**
 * 일봉의 바깥 원천을 묻는 창구. <b>{@code stock}(1층)이 선언하고 {@code price}(1층, KIS 실공급자)가 구현한다.</b>
 * <p>
 * 일봉 테이블은 stock 소유인데 그것을 채울 데이터는 KIS 에 있고 KIS 클라이언트는 price 소유다. 같은 층이라 stock 이 price 를 부를 수
 * 없으므로 {@link PriceQueryPort} 와 같은 방식으로 뒤집는다 — 컴파일 의존은 price → stock 한 방향이다.
 * <p>
 * 구현이 없을 때(fake 모드)는 {@link EmptyCandleSourcePort} 가 빈 목록을 준다. 그때 차트는 시드({@code candles-seed.csv})가 채운다.
 */
public interface CandleSourcePort {

	/**
	 * {@code [from, to]} 의 일봉을 오래된 날부터. 원천에 없으면 빈 목록 — 예외가 아니다. 원천 장애는 구현이 {@code RuntimeException}
	 * 으로 던지고 호출자가 로그만 남긴다.
	 */
	List<CandleData> dailyCandles(String stockCode, LocalDate from, LocalDate to);

	record CandleData(LocalDate tradeDate, long open, long high, long low, long close, long volume) {
	}
}
