package com.finch.domain.stock.service;

import com.finch.domain.stock.StockProperties;
import com.finch.domain.stock.entity.DailyCandle;
import com.finch.domain.stock.port.CandleSourcePort;
import com.finch.domain.stock.port.CandleSourcePort.CandleData;
import com.finch.domain.stock.repository.DailyCandleRepository;
import com.finch.domain.stock.repository.StockRepository;
import com.finch.global.lock.LeaderLock;
import com.finch.global.util.KstTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 일봉을 바깥 원천({@link CandleSourcePort})에서 채운다. 두 경로다.
 * <ol>
 *   <li><b>lazy</b> ({@link #backfillIfEmpty}) — 캔들 API 가 불린 종목에 봉이 하나도 없으면 그 자리에서
 *       {@code backfill-days}(3년) 만큼 받아 넣는다. 전 종목 백필(2,700 × 8회 호출)은 하지 않는다 — 아무도 안 보는
 *       종목의 봉을 미리 받을 이유가 없고 한도만 쓴다.</li>
 *   <li><b>배치</b> ({@link #refreshDaily}) — 16:00 KST, 봉이 있는 종목만 마지막 봉 다음 날부터 오늘까지 받아 더한다. 그 종목들의
 *       {@code stock.previous_close} 를 마지막 봉 종가로 맞춘다.</li>
 * </ol>
 * <b>원천 호출은 트랜잭션 밖이다</b> (backConvention 8장 — 외부 HTTP 는 트랜잭션 밖). 그래서 {@code @Transactional} 대신
 * {@link TransactionTemplate}(REQUIRES_NEW)으로 저장만 짧게 감싼다. 호출자({@code StockService.candles})가 읽기 트랜잭션 안에 있어도
 * 여기 저장은 자기 트랜잭션에서 커밋된다.
 * <p>
 * 배치는 <b>리더만</b> 돈다 ({@link LeaderLock}). 두 인스턴스가 같이 돌면 같은 봉을 두 번 받아 한도만 쓴다.
 */
@Slf4j
@Service
public class CandleSyncService {

	private final DailyCandleRepository dailyCandleRepository;
	private final StockRepository stockRepository;
	private final CandleSourcePort candleSource;
	private final LeaderLock leaderLock;
	private final StockProperties properties;
	private final TransactionTemplate writeTx;

	public CandleSyncService(DailyCandleRepository dailyCandleRepository, StockRepository stockRepository,
		CandleSourcePort candleSource, LeaderLock leaderLock, StockProperties properties,
		PlatformTransactionManager transactionManager) {
		this.dailyCandleRepository = dailyCandleRepository;
		this.stockRepository = stockRepository;
		this.candleSource = candleSource;
		this.leaderLock = leaderLock;
		this.properties = properties;
		this.writeTx = new TransactionTemplate(transactionManager);
		this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 봉이 없는 종목이면 {@code backfill-days} 만큼 받아 넣는다. 있으면 아무것도 하지 않는다 — 두 번째 요청은 원천을 부르지 않는다.
	 * <b>실패해도 던지지 않는다.</b> 차트 요청이 KIS 장애로 500 이 되면 안 된다 — 빈 차트가 맞다. 다음 요청이 다시 시도한다.
	 * <p>
	 * <b>{@code to} 가 오늘이 아니라 어제다.</b> 장중에 부르면 KIS 가 <b>진행 중인 오늘 봉</b>을 함께 주는데, 그것을 저장하면
	 * 영영 미완성으로 굳는다 — {@link #backfillIfEmpty} 는 봉이 하나라도 있으면 다시 부르지 않고, {@link #refreshDailyNow} 는
	 * <b>마지막 봉 다음 날부터</b> 받으므로 오늘을 건너뛰며, {@link #save} 는 이미 있는 날을 거른다. 게다가 그 값으로
	 * {@code stock.previous_close} 까지 맞춰져 그 종목의 등락률이 계속 틀린다. 그래서 <b>확정된 봉만 저장한다</b> —
	 * 오늘 봉은 16:00 배치가 정상 경로로 넣는다.
	 *
	 * @return 넣은 봉 수.
	 */
	public int backfillIfEmpty(String stockCode) {
		if (dailyCandleRepository.existsByStockCode(stockCode)) {
			return 0;
		}
		LocalDate to = LocalDate.now(KstTime.ZONE).minusDays(1);
		LocalDate from = to.minusDays(properties.candle().backfillDays());
		try {
			List<CandleData> fetched = candleSource.dailyCandles(stockCode, from, to);
			int saved = save(stockCode, fetched, to);
			if (saved > 0) {
				log.info("일봉 lazy 적재 code={} rows={} from={} to={}", stockCode, saved, from, to);
			}
			return saved;
		} catch (RuntimeException e) {
			log.warn("일봉 lazy 적재 실패 — 다음 요청이 다시 시도한다 code={}: {}", stockCode, e.getMessage());
			return 0;
		}
	}

	/**
	 * 봉이 있는 종목 전부, 마지막 봉 다음 날부터 오늘까지. 장 마감(15:30) 뒤 16:00 이라 당일 봉이 확정돼 있다.
	 * 종목 하나가 실패해도 나머지는 계속한다.
	 */
	@Scheduled(cron = "${finch.stock.candle.cron:0 0 16 * * *}", zone = "Asia/Seoul")
	public void refreshDaily() {
		if (!leaderLock.isLeader()) {
			return;
		}
		refreshDailyNow();
	}

	/** 스케줄과 리더 판정을 뺀 몸통. 테스트가 직접 부른다. @return 더한 봉 수. */
	public int refreshDailyNow() {
		LocalDate today = LocalDate.now(KstTime.ZONE);
		int added = 0;
		List<String> codes = dailyCandleRepository.findDistinctStockCodes();
		for (String code : codes) {
			try {
				Optional<LocalDate> latest = dailyCandleRepository.findLatestTradeDate(code);
				LocalDate from = latest.map(d -> d.plusDays(1)).orElse(today.minusDays(properties.candle().backfillDays()));
				if (from.isAfter(today)) {
					continue;
				}
				added += save(code, candleSource.dailyCandles(code, from, today), today);
			} catch (RuntimeException e) {
				log.warn("일봉 일일 갱신 실패 code={}: {}", code, e.getMessage());
			}
		}
		log.info("일봉 일일 갱신 종목={} 추가된 봉={}", codes.size(), added);
		return added;
	}

	/**
	 * 저장은 짧은 자기 트랜잭션. <b>원천이 준 날짜 범위 전체</b>에서 이미 있는 날을 걸러 건너뛴다 — 원천은 요청한 {@code from} 보다
	 * 앞의 봉을 섞어 줄 수 있고(KIS 는 영업일 기준으로 창을 채운다), 하나라도 PK (종목, 날짜) 에 걸리면 저장 전체가 롤백된다.
	 * 마지막 봉 종가로 기준가를 맞춘다.
	 *
	 * @param maxDate 이 날짜 뒤의 봉은 버린다. 원천은 요청한 {@code to} 보다 <b>뒤</b>의 봉도 섞어 줄 수 있고, 장중이라면 그것이
	 *                진행 중인 오늘 봉이다. 한 번 저장되면 다시 고칠 경로가 없으므로({@link #backfillIfEmpty} 주석) 여기서 막는다.
	 *                백필은 어제, 일일 갱신은 오늘이다 — 배치는 장 마감 뒤에 돌아 당일 봉이 확정돼 있다.
	 */
	private int save(String stockCode, List<CandleData> fetched, LocalDate maxDate) {
		if (fetched.isEmpty()) {
			return 0;
		}
		LocalDate first = fetched.stream().map(CandleData::tradeDate).min(LocalDate::compareTo).orElseThrow();
		LocalDate last = fetched.stream().map(CandleData::tradeDate).max(LocalDate::compareTo).orElseThrow();
		Set<LocalDate> existing = Set.copyOf(dailyCandleRepository.findTradeDatesBetween(stockCode, first, last));
		List<DailyCandle> candles = fetched.stream()
			.sorted((a, b) -> a.tradeDate().compareTo(b.tradeDate()))
			.filter(c -> !c.tradeDate().isAfter(maxDate))
			.filter(c -> !existing.contains(c.tradeDate()))
			.map(c -> DailyCandle.of(stockCode, c.tradeDate(), c.open(), c.high(), c.low(), c.close(), c.volume()))
			.toList();
		if (candles.isEmpty()) {
			return 0;
		}
		Integer saved = writeTx.execute(status -> {
			dailyCandleRepository.saveAll(candles);
			DailyCandle newest = candles.getLast();
			stockRepository.findById(stockCode).ifPresent(stock -> stock.applyPreviousClose(newest.getClosePrice(), Instant.now()));
			return candles.size();
		});
		return saved == null ? 0 : saved;
	}

	/** 테스트·시연에서 종목의 봉 존재 여부를 볼 때. */
	public boolean hasCandles(String stockCode) {
		return dailyCandleRepository.existsByStockCode(stockCode);
	}
}
