package com.finch.domain.price.feed.kis;

import com.finch.domain.price.MarketIndex;
import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.IndexCache;
import com.finch.domain.price.cache.IndexEntry;
import com.finch.domain.price.feed.IndexFeed;
import com.finch.global.lock.LeaderLock;
import com.finch.global.util.MarketClock;
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
 * 관심을 보지 않기 때문에 <b>멈출 조건이 장 시간뿐이다</b> — {@link #tick()} 이 그것을 본다 (이슈 #76).
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
	private final MarketClock marketClock;
	private final PriceProperties priceProperties;
	private final KisProperties kisProperties;

	private ScheduledExecutorService scheduler;
	private volatile boolean running;

	public KisIndexFeed(KisClient client, KisKeyPool keyPool, IndexCache indexCache, LeaderLock leaderLock,
		MarketClock marketClock, PriceProperties priceProperties, KisProperties kisProperties) {
		this.client = client;
		this.keyPool = keyPool;
		this.indexCache = indexCache;
		this.leaderLock = leaderLock;
		this.marketClock = marketClock;
		this.priceProperties = priceProperties;
		this.kisProperties = kisProperties;
	}

	/**
	 * 전 지수 한 바퀴. <b>장 밖이거나</b> 리더가 아니면 0.
	 * <p>
	 * <b>장 밖에는 부르지 않는다</b> (이슈 #76). 체결이 없어 값이 바뀌지 않는데 10초마다 2회가 나가고, 그만큼 KIS 한도에서
	 * 장중에 쓸 여유가 줄어든다. 종목 시세는 관심 신호가 끊기면 저절로 멈추지만({@link KisPollingFeed}) 지수는 관심을 보지
	 * 않으므로 여기서 막아야 한다 — 화면을 전부 닫아도 나가던 것이 이 호출이다.
	 * <p>
	 * <b>기준은 {@link MarketClock#isOpen()} 이라 애프터마켓(16:00~20:00)까지 채운다.</b> 지수가 그 시간에 갱신되는지 확인되지
	 * 않았고, 갱신되는데 멈추면 종목 시세는 움직이는 옆에서 홈 지수만 4시간 굳는다. 반대 방향(헛호출)은 값이 같은 것을 다시
	 * 쓰는 것뿐이다. 확인되면 {@code sessionNow() == REGULAR} 로 좁힌다 — 그때 아래 {@code IndexQueryService} 의 {@code stale}
	 * 판정도 <b>같은 조건</b>으로 함께 좁혀야 한다. 둘이 어긋나면 멈춘 구간이 "지연" 으로 표시된다.
	 * <p>
	 * {@code always-open}(시연)이면 {@code isOpen()} 이 항상 참이라 지금과 같이 돈다.
	 */
	@Override
	public int tick() {
		if (!marketClock.isOpen() || !leaderLock.isLeader()) {
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
