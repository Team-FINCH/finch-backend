package com.finch.domain.price.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.MarketIndex;
import com.finch.domain.price.cache.IndexCache;
import com.finch.domain.price.cache.IndexEntry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Fake 지수 공급자 — 관심 신호 없이 두 지수를 채우고, 첫 값이 기준이 되며, 이후 걸음이 좁은 폭 안에 있는지 본다.
 * 테스트 설정이 {@code fake.auto-start: false} 라 스케줄러가 돌지 않는다 — {@link FakeIndexFeed#tick()} 을 직접 부른다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FakeIndexFeedTest {

	@Autowired
	private FakeIndexFeed feed;

	@Autowired
	private IndexCache indexCache;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void clearIndices() {
		redisTemplate.delete(List.of("index:KOSPI", "index:KOSDAQ"));
	}

	@Test
	@DisplayName("기동 시 자동으로 돌지 않는다 — 테스트 설정이 auto-start=false 다")
	void doesNotAutoStartInTests() {
		assertThat(feed.isAutoStartup()).isFalse();
		assertThat(feed.isRunning()).isFalse();
	}

	@Test
	@DisplayName("관심 신호 없이 두 지수를 채운다. 처음 보는 지수는 시작값이 곧 전일 종가라 등락이 0 이다")
	void firstTickStartsAtBase() {
		assertThat(feed.tick()).isEqualTo(2);

		for (MarketIndex index : MarketIndex.values()) {
			IndexEntry entry = indexCache.get(index).orElseThrow();
			assertThat(entry.currentValue()).isEqualByComparingTo(FakeIndexFeed.base(index));
			assertThat(entry.previousClose()).isEqualByComparingTo(FakeIndexFeed.base(index));
		}
	}

	@Test
	@DisplayName("다음 틱은 전일 종가를 유지하고, 한 걸음이 ±0.05% 안이며 소수 둘째 자리다")
	void walksWithinNarrowBand() {
		feed.tick();
		feed.tick();

		IndexEntry kospi = indexCache.get(MarketIndex.KOSPI).orElseThrow();
		BigDecimal base = FakeIndexFeed.base(MarketIndex.KOSPI);
		assertThat(kospi.previousClose()).isEqualByComparingTo(base);
		assertThat(kospi.currentValue().scale()).isEqualTo(2);
		BigDecimal band = base.multiply(BigDecimal.valueOf(FakeIndexFeed.MOVE_RATE)).add(new BigDecimal("0.01"));
		assertThat(kospi.currentValue().subtract(base).abs()).isLessThanOrEqualTo(band);
	}
}
