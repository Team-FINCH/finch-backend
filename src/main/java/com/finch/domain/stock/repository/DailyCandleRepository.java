package com.finch.domain.stock.repository;

import com.finch.domain.stock.entity.DailyCandle;
import com.finch.domain.stock.entity.DailyCandleId;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** `daily_candle` 은 stock 도메인 소유다. S10 의 일봉 배치도 이 리포지토리로 쓴다. */
public interface DailyCandleRepository extends JpaRepository<DailyCandle, DailyCandleId> {

	/**
	 * 기간 조회 (apiSpec 5.3). PK (stock_code, trade_date) 가 곧 range scan 인덱스다 (erd.md §2.8).
	 * 오름차순 — 차트는 왼쪽이 과거다.
	 */
	List<DailyCandle> findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(String stockCode, LocalDate from,
		LocalDate to);
}
