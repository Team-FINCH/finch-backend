package com.finch.domain.price.feed.kis;

import com.finch.domain.price.MarketIndex;
import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.IndexCache;
import com.finch.domain.price.cache.IndexEntry;
import com.finch.domain.price.feed.IndexFeed;
import com.finch.global.lock.LeaderLock;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * KIS 지수 공급자 ({@code provider=kis}). {@code index.poll-interval}(10초)마다 KOSPI·KOSDAQ 을 불러 지수 캐시에 넣는다.
 * <p>
 * {@link KisPollingFeed} 와 같은 규칙을 따른다 — <b>리더만 돌고</b>, 한 지수가 실패해도 다른 지수는 부르며, 한도 초과면 이번 틱을
 * 접는다. 다른 점은 <b>관심 신호를 보지 않는다</b>는 것이다. 지수는 두 개뿐이라 누가 보든 안 보든 채운다 (apiSpec 5.7).
 * 호출은 주기당 2회라 초당 한도에 사실상 영향이 없고, 키는 풀에서 돌아가며 쓴다({@link KisKeyPool#next}).
 * <p>
 * 실패하면 캐시를 덮지 않는다. 마지막 값이 남고, {@code stale-after}(60초)가 지나면 조회 쪽이 {@code stale: true} 로 답한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "finch.price.provider", havingValue = "kis")
public class KisIndexFeed implements IndexFeed, SmartLifecycle {

	private final KisClient client;
	private final KisKeyPool keyPool;
	private final IndexCache indexCache;
	private final LeaderLock leaderLock;
	private final PriceProperties priceProperties;
	private final KisProperties kisProperties;

	private ScheduledExecutorService scheduler;
	private volatile boolean running;

	public KisIndexFeed(KisClient client, KisKeyPool keyPool, IndexCache indexCache, LeaderLock leaderLock,
		PriceProperties priceProperties, KisProperties kisProperties) {
		this.client = client;
		this.keyPool = keyPool;
		this.indexCache = indexCache;
		this.leaderLock = leaderLock;
		this.priceProperties = priceProperties;
		this.kisProperties = kisProperties;
	}

	/** 전 지수 한 바퀴. 리더가 아니면 0. */
	@Override
	public int tick() {
		if (!leaderLock.isLeader()) {
			return 0;
		}
		int filled = 0;
		for (MarketIndex index : MarketIndex.values()) {
			try {
				KisIndexQuote quote = client.indexPrice(keyPool.next(), kisCode(index));
				indexCache.put(index, new IndexEntry(quote.currentValue(), quote.previousClose(), Instant.now()));
				filled++;
			} catch (KisException e) {
				if (e.getKind() == KisException.Kind.RATE_LIMITED) {
					log.warn("KIS 한도 초과 — 이번 지수 순회를 접는다 index={}", index);
					break;
				}
				log.warn("KIS 지수 실패 index={} kind={} — 다음 주기에 다시 시도한다: {}", index, e.getKind(), e.getMessage());
			} catch (RuntimeException e) {
				log.warn("KIS 지수 폴링 중 예외 index={}", index, e);
			}
		}
		return filled;
	}

	/** KIS 업종코드. 조회 쪽이 KIS 를 모르게 매핑은 여기에만 둔다 ({@link MarketIndex} 주석). */
	static String kisCode(MarketIndex index) {
		return switch (index) {
			case KOSPI -> "0001";
			case KOSDAQ -> "1001";
		};
	}

	// ---- SmartLifecycle ----

	/** 테스트는 {@code kis.auto-start: false} 다 — {@link KisPollingFeed} 와 같은 스위치를 쓴다. */
	@Override
	public boolean isAutoStartup() {
		return kisProperties.autoStart();
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		long interval = Math.max(1_000, priceProperties.index().pollInterval().toMillis());
		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "kis-index-feed");
			thread.setDaemon(true);
			return thread;
		});
		// 첫 틱은 기다리지 않는다 — 기동 직후 홈을 열면 곧바로 값이 있어야 한다. 리더 락을 아직 못 잡았으면 다음 틱에 채운다.
		scheduler.scheduleWithFixedDelay(this::tickQuietly, 0, interval, TimeUnit.MILLISECONDS);
		running = true;
		log.info("KIS 지수 공급자 시작 interval={}ms", interval);
	}

	@Override
	public void stop() {
		if (scheduler != null) {
			scheduler.shutdownNow();
			scheduler = null;
		}
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void tickQuietly() {
		try {
			tick();
		} catch (RuntimeException e) {
			log.warn("KIS 지수 틱 실패 — 다음 주기에 다시 시도한다", e);
		}
	}
}
