package com.finch.domain.stock.service;

import com.finch.domain.stock.StockProperties;
import com.finch.domain.stock.repository.StockRepository;
import com.finch.global.lock.LeaderLock;
import com.finch.global.util.StockUniverse;
import java.time.Duration;
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
 * <p>
 * <b>일봉 워밍은 배경 스레드다.</b> 서비스 종목 30개 × 8회 호출을 리미터(초당 2건) 안에서 하면 약 2분인데, 그동안 기동을 붙잡으면
 * readiness 가 늦어져 롤링 배포가 밀린다. 리더만 한다 — 두 파드가 같이 받으면 같은 봉을 두 번 받아 한도만 쓴다. 리더 락은
 * 기동 직후 Redis 왕복 뒤에 잡히므로 몇 초 기다렸다 본다. 그때 리더가 아니면 건너뛴다 — 리더 파드가 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockStartupRunner {

	/** 리더 락 첫 획득(SmartLifecycle 시작 직후 Redis SET NX)을 기다리는 시간. 갱신 주기(3초)보다 넉넉히. */
	static final Duration WARM_UP_DELAY = Duration.ofSeconds(5);

	private final StockMasterSyncService masterSyncService;
	private final CandleSeeder candleSeeder;
	private final CandleSyncService candleSyncService;
	private final StockRepository stockRepository;
	private final StockProperties properties;
	private final StockUniverse universe;
	private final LeaderLock leaderLock;

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
		if (properties.candle().warmUpOnStartup() && universe.restricted()) {
			Thread thread = new Thread(this::warmUpQuietly, "candle-warm-up");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private void warmUpQuietly() {
		try {
			Thread.sleep(WARM_UP_DELAY.toMillis());
			if (!leaderLock.isLeader()) {
				log.info("일봉 워밍 건너뜀 — 리더가 아니다");
				return;
			}
			candleSyncService.warmUp(universe.codes());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			log.warn("일봉 워밍 실패 — 첫 차트 조회가 lazy 적재로 대신한다", e);
		}
	}
}
