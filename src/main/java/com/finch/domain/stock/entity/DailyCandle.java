package com.finch.domain.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * `daily_candle` 테이블 (erd.md §2.8) — 일봉. 종목당 거래일 하나다.
 * <p>
 * 불변이다 ({@link Immutable}). 하루가 끝난 봉은 바뀌지 않고, 잘못 들어간 행은 지우고 다시 넣는다 (S10 배치).
 * 지금은 시드({@code candles-seed.csv})가 채우고 S10 이 실데이터로 교체한다.
 * <p>
 * {@code trade_date} 는 {@code DATE} 라 {@code LocalDate} 다. 시각이 없는 값에 {@code Instant} 를 쓰면 시간대에 따라 날짜가 밀린다.
 */
@Entity
@Table(name = "daily_candle")
@IdClass(DailyCandleId.class)
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyCandle {

	/** {@code CHAR(6)} 매핑 이유는 {@link Stock#getStockCode()} 주석. */
	@Id
	@JdbcTypeCode(Types.CHAR)
	@Column(name = "stock_code", length = 6, nullable = false)
	private String stockCode;

	@Id
	@Column(nullable = false)
	private LocalDate tradeDate;

	@Column(nullable = false)
	private long openPrice;

	@Column(nullable = false)
	private long highPrice;

	@Column(nullable = false)
	private long lowPrice;

	@Column(nullable = false)
	private long closePrice;

	@Column(nullable = false)
	private long volume;

	public static DailyCandle of(String stockCode, LocalDate tradeDate, long open, long high, long low, long close,
		long volume) {
		DailyCandle candle = new DailyCandle();
		candle.stockCode = stockCode;
		candle.tradeDate = tradeDate;
		candle.openPrice = open;
		candle.highPrice = high;
		candle.lowPrice = low;
		candle.closePrice = close;
		candle.volume = volume;
		return candle;
	}
}
