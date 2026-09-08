package com.finch.domain.price.feed;

import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.InterestRegistry;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.global.util.KstTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
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
 * 가짜 시세 공급자. 관심 종목의 가격을 랜덤워크로 흔든다.
 * <p>
 * <b>KIS 앱키 없이 주문(S9)까지 끝내기 위해 있다.</b> 체결가는 "서버가 보유한 최신 수신 가격"인데(featureSpec 1.1) 그 값을
 * 만들어 주는 것이 없으면 주문 스토리가 통째로 막힌다. S10 이 붙으면 {@code provider=kis} 로 바뀌고 이 빈은 만들어지지 않는다.
 * <p>
 * <b>전일 종가를 읽지 않는다.</b> 그러려면 price(1층)가 stock(1층)의 {@code previous_close} 를 봐야 하는데 같은 층 참조다
 * (backConvention 2.4 규칙 2). 그래서 처음 보는 종목은 {@code base-price} 에서 시작하고 <b>그 첫 값을 자기 기준가로 삼는다</b>.
 * KIS 는 현재가 응답에 전일 종가가 실려 와서 이 문제가 없다.
 * <p>
 * ⚠️ 그 결과 <b>시연 화면의 숫자가 어긋난다.</b> 종목 상세의 {@code previousClose} 는 {@code stock} 테이블의 KIS 기준가(삼성전자
 * 255,500)인데 현재가는 여기서 만든 50,000 근처다. S10 이 붙으면 사라지는 문제지만, 그 전에 시연한다면 {@code base-price} 를
 * 손보거나 이 결정을 다시 봐야 한다 (MR 본문에 적었다).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "finch.price.provider", havingValue = "fake", matchIfMissing = true)
public class FakePriceFeed implements PriceFeed, SmartLifecycle {

	private final PriceCache priceCache;
	private final InterestRegistry interestRegistry;
	private final PriceProperties properties;
	private final Clock clock;

	private ScheduledExecutorService scheduler;
	private volatile boolean running;

	/**
	 * 생성자가 둘이라 스프링에게 어느 쪽인지 알려 줘야 한다. 표시가 없으면 기본 생성자를 찾다가
	 * {@code NoSuchMethodException} 으로 기동에 실패한다 ({@code MarketClock} 이 같은 함정을 적어 두었다).
	 */
	@Autowired
	public FakePriceFeed(PriceCache priceCache, InterestRegistry interestRegistry, PriceProperties properties) {
		this(priceCache, interestRegistry, properties, Clock.systemUTC());
	}

	/** 시각을 고정하려는 테스트가 쓴다. */
	public FakePriceFeed(PriceCache priceCache, InterestRegistry interestRegistry, PriceProperties properties,
		Clock clock) {
		this.priceCache = priceCache;
		this.interestRegistry = interestRegistry;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 관심 종목 한 바퀴. 관심이 없으면 아무 일도 하지 않는다 — 전 종목을 매초 흔들 이유가 없고, 그러면 캐시가 3천 개로 불어난다.
	 * <p>
	 * 처음 보는 종목은 {@code base-price} 그대로 넣는다. 기준가도 같은 값이라 <b>첫 응답의 등락은 0</b> 이다. 두 번째 틱부터 움직인다.
	 * <p>
	 * <b>당일 봉을 함께 누적한다</b> ({@code PriceEntry.session*}). KIS 는 현재가 응답에 시가·고가·저가가 실려 오지만 여기는
	 * 만들어 낼 근거가 없으므로 <b>그날 처음 본 값을 시가로 삼고</b> 이후 최대·최소를 갱신한다. 거래량은 0 이다 — 없는 것을
	 * 지어내면 화면에 그럴듯한 거짓 숫자가 뜬다. 날짜(KST)가 바뀌면 새 세션으로 다시 시작한다 — 캐시 키에 만료가 없어
	 * 값이 자정을 넘겨 남는다.
	 */
	@Override
	public int tick() {
		Set<String> codes = interestRegistry.interested();
		if (codes.isEmpty()) {
			return 0;
		}
		Instant now = Instant.now(clock);
		LocalDate today = LocalDate.ofInstant(now, KstTime.ZONE);
		for (String code : codes) {
			PriceEntry previous = priceCache.get(code).orElse(null);
			if (previous == null) {
				long base = properties.fake().basePrice();
				priceCache.put(code, new PriceEntry(base, base, now, today, base, base, base, 0L));
			} else {
				long price = walk(previous.currentPrice());
				boolean sameSession = today.equals(previous.sessionDate());
				long open = sameSession && previous.sessionOpen() != null ? previous.sessionOpen() : price;
				long high = sameSession && previous.sessionHigh() != null ? Math.max(previous.sessionHigh(), price) : price;
				long low = sameSession && previous.sessionLow() != null ? Math.min(previous.sessionLow(), price) : price;
				priceCache.put(code,
					new PriceEntry(price, previous.previousClose(), now, today, open, high, low, 0L));
			}
		}
		return codes.size();
	}

	/** ±{@code max-move-rate} 안에서 한 걸음. 하한이 있는 이유 — 0 으로 수렴하면 등락률이 무너지고 주문 금액도 0 이 된다. */
	private long walk(long current) {
		double rate = ThreadLocalRandom.current().nextDouble(-properties.fake().maxMoveRate(),
			properties.fake().maxMoveRate());
		return Math.max(properties.fake().minPrice(), Math.round(current * (1 + rate)));
	}

	// ---- SmartLifecycle ----

	/**
	 * <b>테스트는 {@code auto-start: false} 다.</b> 배경 스레드가 매초 캐시를 흔들면 {@code stale} 판정처럼 시각에 기대는 테스트가
	 * 불안정해진다. 테스트는 {@link #tick()} 을 직접 부른다.
	 */
	@Override
	public boolean isAutoStartup() {
		return properties.fake().autoStart();
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		long interval = Math.max(1, properties.fake().tickInterval().toMillis());
		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "fake-price-feed");
			thread.setDaemon(true);
			return thread;
		});
		// 예외가 새면 ScheduledExecutorService 는 다음 실행을 조용히 멈춘다. 여기서 잡아 로그만 남긴다.
		scheduler.scheduleWithFixedDelay(this::tickQuietly, interval, interval, TimeUnit.MILLISECONDS);
		running = true;
		log.info("Fake 시세 공급자 시작 interval={}ms basePrice={}", interval, properties.fake().basePrice());
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
			log.warn("Fake 시세 틱 실패 — 다음 주기에 다시 시도한다", e);
		}
	}
}
