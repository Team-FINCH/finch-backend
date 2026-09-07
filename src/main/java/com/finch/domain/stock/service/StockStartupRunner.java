package com.finch.domain.stock.service;

import com.finch.domain.stock.StockProperties;
import com.finch.domain.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 시 1회 — {@code stock} 이 비어 있으면 마스터를 적재하고, 그 뒤 {@code daily_candle} 이 비어 있으면 시드를 넣는다.
 * 순서가 있다: 봉은 종목 FK 를 든다. 그래서 리스너 둘이 아니라 하나에서 차례로 부른다.
 * <p>
 * {@code ApplicationReadyEvent} 인 이유 — 그때는 트랜잭션 매니저·리포지토리가 전부 준비돼 있고, 헬스체크가 열리기 직전이다.
 * 실패해도 기동은 계속된다 — 종목이 없으면 검색이 빈 목록일 뿐이고, 정기 동기화가 07:00 에 다시 시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockStartupRunner {

	private final StockMasterSyncService masterSyncService;
	private final CandleSeeder candleSeeder;
	private final StockRepository stockRepository;
	private final StockProperties properties;

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		try {
			if (properties.master().syncOnStartup() && stockRepository.count() == 0) {
				log.info("종목 마스터가 비어 있어 기동 시 적재한다");
				masterSyncService.sync();
			}
			candleSeeder.seedIfEmpty();
		} catch (RuntimeException e) {
			log.error("기동 시 종목 적재 실패 — 정기 동기화가 다시 시도한다", e);
		}
	}
}
