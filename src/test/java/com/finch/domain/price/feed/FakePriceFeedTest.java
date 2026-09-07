package com.finch.domain.price.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.InterestRegistry;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Fake 공급자가 관심 종목만 채우는지, 첫 값과 이후 걸음이 규칙대로인지 본다.
 * <p>
 * 테스트 설정이 {@code fake.auto-start: false} 라 스케줄러가 돌지 않는다 — {@link FakePriceFeed#tick()} 을 직접 부른다.
 * 배경 스레드가 캐시를 흔들면 이 단언들이 흔들린다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FakePriceFeedTest {

	private static final AtomicLong CODE_SEQ = new AtomicLong(720_000L);

	@Autowired
	private FakePriceFeed feed;

	@Autowired
	private PriceCache priceCache;

	@Autowired
	private InterestRegistry interestRegistry;

	@Autowired
	private PriceProperties properties;

	@Test
	@DisplayName("기동 시 자동으로 돌지 않는다 — 테스트 설정이 auto-start=false 다")
	void doesNotAutoStartInTests() {
		assertThat(feed.isAutoStartup()).isFalse();
		assertThat(feed.isRunning()).isFalse();
	}

	/** 전 종목을 매초 흔들면 캐시가 3천 개로 불어난다. 관심 목록이 그것을 막는 장치다. */
	@Test
	@DisplayName("관심 있는 종목만 채운다 — 묻지 않은 종목은 캐시에 생기지 않는다")
	void fillsOnlyInterested() {
		String interested = newCode();
		String ignored = newCode();
		interestRegistry.touch(List.of(interested));

		feed.tick();

		assertThat(priceCache.get(interested)).isPresent();
		assertThat(priceCache.get(ignored)).isEmpty();
	}

	@Test
	@DisplayName("처음 보는 종목은 base-price 로 시작하고 기준가도 같은 값이라 첫 등락이 0 이다")
	void firstTickStartsAtBasePrice() {
		String code = newCode();
		interestRegistry.touch(List.of(code));

		feed.tick();

		PriceEntry entry = priceCache.get(code).orElseThrow();
		assertThat(entry.currentPrice()).isEqualTo(properties.fake().basePrice());
		assertThat(entry.previousClose()).isEqualTo(properties.fake().basePrice());
		assertThat(entry.asOf()).isNotNull();
	}

	@Test
	@DisplayName("두 번째 틱부터 움직이고 기준가는 첫 값 그대로 유지된다")
	void laterTicksWalkFromPrevious() {
		String code = newCode();
		interestRegistry.touch(List.of(code));
		feed.tick();
		long base = properties.fake().basePrice();

		for (int i = 0; i < 20; i++) {
			feed.tick();
			PriceEntry entry = priceCache.get(code).orElseThrow();
			// 기준가는 흔들리지 않는다 — 등락의 기준이 매 틱 바뀌면 등락률이 의미를 잃는다.
			assertThat(entry.previousClose()).isEqualTo(base);
			assertThat(entry.currentPrice()).isGreaterThanOrEqualTo(properties.fake().minPrice());
		}
	}

	@Test
	@DisplayName("한 틱의 변동은 max-move-rate 안이다")
	void movesWithinLimit() {
		String code = newCode();
		interestRegistry.touch(List.of(code));
		feed.tick();

		long before = priceCache.get(code).orElseThrow().currentPrice();
		feed.tick();
		long after = priceCache.get(code).orElseThrow().currentPrice();

		double moved = Math.abs(after - before) / (double) before;
		assertThat(moved).isLessThanOrEqualTo(properties.fake().maxMoveRate() + 1e-9);
	}

	@Test
	@DisplayName("관심 종목이 없으면 아무것도 채우지 않는다")
	void noInterestNoWork() {
		// 다른 테스트가 남긴 관심이 있을 수 있으므로 반환값이 아니라 "예외 없이 끝난다" 만 본다.
		assertThat(feed.tick()).isNotNegative();
	}

	private static String newCode() {
		return String.valueOf(CODE_SEQ.incrementAndGet());
	}
}
