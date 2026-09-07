package com.finch.domain.stock.repository;

import com.finch.domain.stock.entity.DailyCandle;
import com.finch.domain.stock.entity.DailyCandleId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** `daily_candle` 은 stock 도메인 소유다. S10 의 일봉 배치도 이 리포지토리로 쓴다. */
public interface DailyCandleRepository extends JpaRepository<DailyCandle, DailyCandleId> {

	/**
	 * 기간 조회 (apiSpec 5.3). PK (stock_code, trade_date) 가 곧 range scan 인덱스다 (erd.md §2.8).
	 * 오름차순 — 차트는 왼쪽이 과거다.
	 */
	List<DailyCandle> findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(String stockCode, LocalDate from,
		LocalDate to);

	/** lazy 적재의 판정 — 봉이 하나라도 있으면 원천을 부르지 않는다 ({@code CandleSyncService}). */
	boolean existsByStockCode(String stockCode);

	/** 일일 갱신 대상 = 봉이 있는 종목. 전 종목이 아니다. */
	@Query("select distinct c.stockCode from DailyCandle c")
	List<String> findDistinctStockCodes();

	/** 마지막 봉의 날. 일일 갱신이 그 다음 날부터 받는다. */
	@Query("select max(c.tradeDate) from DailyCandle c where c.stockCode = :stockCode")
	Optional<LocalDate> findLatestTradeDate(String stockCode);

	/** 이미 있는 날은 건너뛴다 — PK (종목, 날짜) 충돌로 저장 전체가 롤백되지 않게. */
	@Query("select c.tradeDate from DailyCandle c where c.stockCode = :stockCode and c.tradeDate between :from and :to")
	List<LocalDate> findTradeDatesBetween(String stockCode, LocalDate from, LocalDate to);
}
