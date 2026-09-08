package com.finch.domain.price.feed.kis;

import com.finch.domain.price.cache.InterestRegistry;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.domain.price.event.PriceObservedEvent;
import com.finch.domain.price.feed.PriceFeed;
import com.finch.global.lock.LeaderLock;
import com.finch.global.util.KstTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * KIS REST 폴링 공급자 ({@code provider=kis}). 관심 종목을 {@code poll-interval}(3초)마다 순회해 캐시에 넣는다 — Fake 와 같은 자리,
 * 같은 {@link PriceFeed} 다. 소비 측은 어느 쪽이 채웠는지 모른다.
 * <p>
 * <b>리더만 돈다.</b> 매 틱 {@link LeaderLock#isLeader()} 를 보고 아니면 아무것도 하지 않는다. 리더가 바뀌면 다음 틱부터 새 리더가
 * 이어받는다 — 캐시와 토큰이 Redis 에 있어 넘겨줄 상태가 없다.
 * <p>
 * <b>종목을 키 수로 나눠 키마다 한 스레드씩 병렬로 돈다</b> ({@link KisKeyPool#partition}). 키별 리미터가 초당 한도를 지키므로
 * 키가 N 개면 틱 하나에 N 배를 부를 수 있다 — 이것이 키 풀의 목적이다. 키가 하나면 스레드도 하나다.
 * <p>
 * 한 종목이 실패해도 나머지는 계속한다. 단 <b>한도 초과({@code RATE_LIMITED})는 그 키의 이번 틱을 접는다</b> — 계속 두드리면 더 맞는다.
 * 순회가 주기보다 오래 걸리면(종목이 수용량을 넘으면) 다음 틱은 그만큼 늦게 시작한다 — apiSpec 5.6 관계식 4 의 "자연 열화" 다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "finch.price.provider", havingValue = "kis")
public class KisPollingFeed implements PriceFeed, SmartLifecycle {

	private final KisClient client;
	private final KisKeyPool keyPool;
	private final PriceCache priceCache;
	private final InterestRegistry interestRegistry;
	private final LeaderLock leaderLock;
	private final ApplicationEventPublisher eventPublisher;
	private final KisProperties properties;
	/** 종목별 마지막으로 stock 에 알린 값. 같으면 다시 알리지 않는다. */
	private final Map<String, PriceObservedEvent> lastObserved = new ConcurrentHashMap<>();

	private ScheduledExecutorService scheduler;
	private ExecutorService workers;
	private volatile boolean running;

	public KisPollingFeed(KisClient client, KisKeyPool keyPool, PriceCache priceCache, InterestRegistry interestRegistry,
		LeaderLock leaderLock, ApplicationEventPublisher eventPublisher, KisProperties properties) {
		this.client = client;
		this.keyPool = keyPool;
		this.priceCache = priceCache;
		this.interestRegistry = interestRegistry;
		this.leaderLock = leaderLock;
		this.eventPublisher = eventPublisher;
		this.properties = properties;
	}

	/** 관심 종목 한 바퀴. 리더가 아니면 0. */
	@Override
	public int tick() {
		if (!leaderLock.isLeader()) {
			return 0;
		}
		Set<String> codes = interestRegistry.interested();
		if (codes.isEmpty()) {
			return 0;
		}
		Map<KisCredential, List<String>> byKey = keyPool.partition(new ArrayList<>(codes));
		AtomicInteger filled = new AtomicInteger();
		if (byKey.size() == 1) {
			Map.Entry<KisCredential, List<String>> only = byKey.entrySet().iterator().next();
			filled.addAndGet(pollWith(only.getKey(), only.getValue()));
		} else {
			List<Future<Integer>> futures = new ArrayList<>();
			for (Map.Entry<KisCredential, List<String>> entry : byKey.entrySet()) {
				futures.add(workers().submit(() -> pollWith(entry.getKey(), entry.getValue())));
			}
			for (Future<Integer> future : futures) {
				try {
					filled.addAndGet(future.get());
				} catch (Exception e) {
					log.warn("KIS 폴링 스레드 실패", e);
				}
			}
		}
		return filled.get();
	}

	/** 한 키가 맡은 종목들. 순서대로, 리미터가 속도를 잡는다. */
	private int pollWith(KisCredential key, List<String> codes) {
		int filled = 0;
		for (String code : codes) {
			try {
				KisQuote quote = client.currentPrice(key, code);
				Instant now = Instant.now();
				// 당일 봉은 누적하지 않는다 — KIS 현재가 응답이 그날의 시가·고가·저가·누적거래량을 매번 완결된 값으로 준다.
				// 우리가 관측한 것만 모으면 폴링을 시작하기 전의 움직임이 빠진다.
				priceCache.put(code, new PriceEntry(quote.currentPrice(), quote.previousClose(), now,
					LocalDate.ofInstant(now, KstTime.ZONE), quote.sessionOpen(), quote.sessionHigh(),
					quote.sessionLow(), quote.sessionVolume()));
				publishIfChanged(code, quote);
				filled++;
			} catch (KisException e) {
				if (e.getKind() == KisException.Kind.RATE_LIMITED) {
					log.warn("KIS 한도 초과 — 이 키의 이번 순회를 접는다 key={} 남은 종목={}", key.label(),
						codes.size() - codes.indexOf(code));
					break;
				}
				log.warn("KIS 현재가 실패 key={} code={} kind={} — 다음 주기에 다시 시도한다: {}", key.label(), code,
					e.getKind(), e.getMessage());
			} catch (RuntimeException e) {
				log.warn("KIS 폴링 중 예외 key={} code={}", key.label(), code, e);
			}
		}
		return filled;
	}

	private void publishIfChanged(String code, KisQuote quote) {
		PriceObservedEvent event = new PriceObservedEvent(code, quote.previousClose(), quote.suspended(),
			quote.suspendedReason());
		if (!event.equals(lastObserved.put(code, event))) {
			eventPublisher.publishEvent(event);
		}
	}

	private ExecutorService workers() {
		if (workers == null) {
			workers = Executors.newFixedThreadPool(keyPool.size(), runnable -> {
				Thread thread = new Thread(runnable, "kis-poll-worker");
				thread.setDaemon(true);
				return thread;
			});
		}
		return workers;
	}

	// ---- SmartLifecycle ----

	/** 테스트는 {@code auto-start: false} 다 — Fake 와 같은 이유로 배경 스레드가 캐시를 흔들지 않게 한다. */
	@Override
	public boolean isAutoStartup() {
		return properties.autoStart();
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		long interval = Math.max(500, properties.pollInterval().toMillis());
		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "kis-polling-feed");
			thread.setDaemon(true);
			return thread;
		});
		// 예외가 새면 ScheduledExecutorService 는 다음 실행을 조용히 멈춘다. 여기서 잡아 로그만 남긴다.
		scheduler.scheduleWithFixedDelay(this::tickQuietly, interval, interval, TimeUnit.MILLISECONDS);
		running = true;
		log.info("KIS 폴링 공급자 시작 interval={}ms keys={} ratePerSecond(키당)={}", interval, keyPool.size(),
			properties.ratePerSecond());
	}

	@Override
	public void stop() {
		if (scheduler != null) {
			scheduler.shutdownNow();
			scheduler = null;
		}
		if (workers != null) {
			workers.shutdownNow();
			workers = null;
		}
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void tickQuietly() {
		try {
			int filled = tick();
			if (filled > 0) {
				log.debug("KIS 폴링 filled={}", filled);
			}
		} catch (RuntimeException e) {
			log.warn("KIS 폴링 틱 실패 — 다음 주기에 다시 시도한다", e);
		}
	}
}
