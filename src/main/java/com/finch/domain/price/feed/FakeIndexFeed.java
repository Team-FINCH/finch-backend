package com.finch.domain.price.feed;

import com.finch.domain.price.MarketIndex;
import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.IndexCache;
import com.finch.domain.price.cache.IndexEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 가짜 지수 공급자. 로컬·테스트에서 홈 지수 자리에 값이 보이게 한다 — {@link FakePriceFeed} 와 같은 자리다.
 * <p>
 * 처음 보는 지수는 {@link #base} 에서 시작하고 <b>그 값을 전일 종가로 삼는다</b>. 그래서 첫 응답의 등락은 0 이고 이후 틱마다
 * 기준에서 멀어진다. 한 걸음은 ±{@value #MOVE_RATE} 다 — 종목용 {@code max-move-rate}(0.5%)를 그대로 쓰면 지수가 몇 분 만에
 * 수십 퍼센트를 움직여 화면이 그럴듯하지 않다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "finch.price.provider", havingValue = "fake", matchIfMissing = true)
public class FakeIndexFeed implements IndexFeed, SmartLifecycle {

	static final double MOVE_RATE = 0.0005;

	private final IndexCache indexCache;
	private final PriceProperties properties;
	private final Clock clock;

	private ScheduledExecutorService scheduler;
	private volatile boolean running;

	@Autowired
	public FakeIndexFeed(IndexCache indexCache, PriceProperties properties) {
		this(indexCache, properties, Clock.systemUTC());
	}

	/** 시각을 고정하려는 테스트가 쓴다. */
	public FakeIndexFeed(IndexCache indexCache, PriceProperties properties, Clock clock) {
		this.indexCache = indexCache;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public int tick() {
		Instant now = Instant.now(clock);
		for (MarketIndex index : MarketIndex.values()) {
			IndexEntry previous = indexCache.get(index).orElse(null);
			if (previous == null) {
				BigDecimal base = base(index);
				indexCache.put(index, new IndexEntry(base, base, now));
			} else {
				indexCache.put(index, new IndexEntry(walk(previous.currentValue()), previous.previousClose(), now));
			}
		}
		return MarketIndex.values().length;
	}

	/** 시작값. 실제 지수와 비슷한 크기면 충분하다 — 화면 폭을 확인하는 용도다. */
	static BigDecimal base(MarketIndex index) {
		return switch (index) {
			case KOSPI -> new BigDecimal("2600.00");
			case KOSDAQ -> new BigDecimal("800.00");
		};
	}

	private static BigDecimal walk(BigDecimal current) {
		double rate = ThreadLocalRandom.current().nextDouble(-MOVE_RATE, MOVE_RATE);
		return current.multiply(BigDecimal.valueOf(1 + rate)).setScale(2, RoundingMode.HALF_UP);
	}

	// ---- SmartLifecycle ----

	/** 테스트는 {@code fake.auto-start: false} 다 — {@link FakePriceFeed} 와 같은 스위치를 쓴다. */
	@Override
	public boolean isAutoStartup() {
		return properties.fake().autoStart();
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		long interval = Math.max(1, properties.index().pollInterval().toMillis());
		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "fake-index-feed");
			thread.setDaemon(true);
			return thread;
		});
		// 첫 틱은 기다리지 않는다 — 기동 직후 홈을 열면 곧바로 값이 있어야 한다.
		scheduler.scheduleWithFixedDelay(this::tickQuietly, 0, interval, TimeUnit.MILLISECONDS);
		running = true;
		log.info("Fake 지수 공급자 시작 interval={}ms", interval);
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
			log.warn("Fake 지수 틱 실패 — 다음 주기에 다시 시도한다", e);
		}
	}
}
