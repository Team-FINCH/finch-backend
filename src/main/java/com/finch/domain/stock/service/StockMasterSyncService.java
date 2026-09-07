package com.finch.domain.stock.service;

import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.master.CsvSeedLoader;
import com.finch.domain.stock.master.KisMasterFileLoader;
import com.finch.domain.stock.master.StockMasterLoadException;
import com.finch.domain.stock.master.StockMasterLoader;
import com.finch.domain.stock.master.StockMasterRow;
import com.finch.domain.stock.repository.StockRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종목 마스터를 {@code stock} 테이블에 동기화한다 (erd.md §1.1 — 마스터는 백엔드 DB 가 소유하고 배치가 채운다).
 * <p>
 * <b>UPSERT 이고 지우지 않는다.</b> 마스터에 있으면 갱신(또는 신설)하고, <b>전 종목 출처</b>에서 사라진 종목은
 * {@code is_active=false} 다. trade·holding 이 FK 로 매달려 있어 행을 지울 수 없고, 검색은 활성만 보면 된다.
 * <p>
 * 출처 선택: {@code finch.stock.master.source=kis} 면 KIS 파일을 먼저 시도하고 실패(다운로드·포맷)하면 <b>WARN 을 남기고 CSV 시드로
 * 폴백</b>한다. 실패가 기동을 막지 않는다 — 종목 검색은 시드 300 종목으로도 시연이 된다. 폴백한 CSV 는 일부라 비활성화를
 * 하지 않는다 ({@link StockMasterLoader#complete()}).
 * <p>
 * 언제 도나: 기동 시 테이블이 비어 있으면 1회({@link StockStartupRunner}), 그리고 매일 07:00 KST. 두 인스턴스가 같은 시각에
 * 돌아도 같은 UPSERT 를 두 번 할 뿐이라 리더 락이 없다 ({@code PaymentExpiryScheduler} 와 같은 판단).
 */
@Slf4j
@Service
public class StockMasterSyncService {

	private final ObjectProvider<KisMasterFileLoader> kisLoader;
	private final CsvSeedLoader csvLoader;
	private final StockRepository stockRepository;

	public StockMasterSyncService(ObjectProvider<KisMasterFileLoader> kisLoader, CsvSeedLoader csvLoader,
		StockRepository stockRepository) {
		this.kisLoader = kisLoader;
		this.csvLoader = csvLoader;
		this.stockRepository = stockRepository;
	}

	/**
	 * @param source      실제로 쓴 출처.
	 * @param deactivated 전 종목 출처에서 사라져 비활성화한 수. 일부 출처면 언제나 0.
	 */
	public record SyncResult(String source, int inserted, int updated, int deactivated, int total) {
	}

	@Scheduled(cron = "${finch.stock.master.cron:0 0 7 * * *}", zone = "Asia/Seoul")
	public void scheduled() {
		try {
			SyncResult result = sync();
			log.info("종목 마스터 정기 동기화 {}", result);
		} catch (RuntimeException e) {
			// 스케줄러 스레드에서 예외가 새면 다음 실행이 막힐 수 있다. 여기서 끝낸다.
			log.error("종목 마스터 정기 동기화 실패", e);
		}
	}

	/**
	 * 한 트랜잭션이다. 중간에 실패하면 이전 마스터가 그대로 남는다 — 반쯤 갱신된 목록보다 어제 목록이 낫다.
	 * <p>
	 * 전부 메모리에 올려 대조한다. 종목이 3천 개 남짓이라 행마다 SELECT 하는 것보다 {@code findAll} 한 번이 싸다.
	 */
	@Transactional
	public SyncResult sync() {
		Loaded loaded = load();
		Instant now = Instant.now();

		Map<String, Stock> existing = new HashMap<>();
		for (Stock stock : stockRepository.findAll()) {
			existing.put(stock.getStockCode(), stock);
		}

		int inserted = 0;
		int updated = 0;
		Set<String> seen = new HashSet<>();
		List<Stock> toInsert = new ArrayList<>();
		for (StockMasterRow row : loaded.rows()) {
			if (!seen.add(row.stockCode())) {
				continue; // 같은 코드가 두 번 오면 첫 행만 — 코스피·코스닥 파일이 겹칠 일은 없지만 시드가 손으로 편집될 수 있다
			}
			Stock stock = existing.get(row.stockCode());
			if (stock == null) {
				toInsert.add(Stock.of(row.stockCode(), row.stockName(), row.market(), row.suspended(), row.suspendedReason(),
					row.referencePrice(), now));
				inserted++;
			} else {
				stock.applyMaster(row.stockName(), row.market(), row.suspended(), row.suspendedReason(), row.referencePrice(), now);
				updated++;
			}
		}
		stockRepository.saveAll(toInsert);

		int deactivated = 0;
		if (loaded.complete()) {
			for (Stock stock : existing.values()) {
				if (stock.isActive() && !seen.contains(stock.getStockCode())) {
					stock.deactivate(now);
					deactivated++;
				}
			}
		}
		SyncResult result = new SyncResult(loaded.source(), inserted, updated, deactivated, seen.size());
		log.info("종목 마스터 동기화 {}", result);
		return result;
	}

	private record Loaded(String source, boolean complete, List<StockMasterRow> rows) {
	}

	/** KIS 가 있으면 KIS, 실패하면 CSV. KIS 빈이 없으면(csv 모드) 바로 CSV. */
	private Loaded load() {
		KisMasterFileLoader kis = kisLoader.getIfAvailable();
		if (kis != null) {
			try {
				return new Loaded(kis.sourceName(), kis.complete(), kis.load());
			} catch (StockMasterLoadException e) {
				log.warn("KIS 마스터 적재 실패 — CSV 시드로 폴백한다: {}", e.getMessage());
			}
		}
		return new Loaded(csvLoader.sourceName(), csvLoader.complete(), csvLoader.load());
	}
}
