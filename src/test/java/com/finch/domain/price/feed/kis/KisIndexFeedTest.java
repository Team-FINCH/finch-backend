package com.finch.domain.price.feed.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.MarketIndex;
import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.IndexCache;
import com.finch.domain.price.cache.IndexEntry;
import com.finch.global.lock.LeaderLock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 지수 폴링 한 틱 — 리더만 돌고, 두 지수를 업종코드로 불러 캐시에 넣고, 실패한 지수는 캐시를 덮지 않는다.
 * KIS 클라이언트·리더 락은 목이고 캐시는 컨테이너의 진짜 Redis 다 ({@code KisPollingFeedTest} 와 같은 구성).
 * <p>
 * 지수 키는 둘뿐이라 다른 테스트와 같은 키를 쓴다. 그래서 테스트마다 먼저 지운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KisIndexFeedTest {

	private static final KisCredential A = new KisCredential("ka", "sa", "a");

	@Autowired
	private IndexCache indexCache;

	@Autowired
	private PriceProperties priceProperties;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void clearIndices() {
		redisTemplate.delete(List.of("index:KOSPI", "index:KOSDAQ"));
	}

	@Test
	@DisplayName("리더가 아니면 아무것도 부르지 않는다")
	void nonLeaderDoesNothing() {
		KisClient client = mock(KisClient.class);

		assertThat(feed(client, false).tick()).isZero();
		verifyNoInteractions(client);
		assertThat(indexCache.getAll()).isEmpty();
	}

	@Test
	@DisplayName("KOSPI 는 0001, KOSDAQ 은 1001 로 불러 현재 지수와 되돌린 전일 종가를 캐시에 넣는다")
	void fillsBothIndices() {
		KisClient client = mock(KisClient.class);
		given(client.indexPrice(any(), eq("0001")))
			.willReturn(new KisIndexQuote(new BigDecimal("2600.54"), new BigDecimal("-12.31")));
		given(client.indexPrice(any(), eq("1001")))
			.willReturn(new KisIndexQuote(new BigDecimal("793.84"), new BigDecimal("0.95")));

		assertThat(feed(client, true).tick()).isEqualTo(2);

		IndexEntry kospi = indexCache.get(MarketIndex.KOSPI).orElseThrow();
		assertThat(kospi.currentValue()).isEqualByComparingTo("2600.54");
		assertThat(kospi.previousClose()).isEqualByComparingTo("2612.85");
		IndexEntry kosdaq = indexCache.get(MarketIndex.KOSDAQ).orElseThrow();
		assertThat(kosdaq.currentValue()).isEqualByComparingTo("793.84");
		assertThat(kosdaq.previousClose()).isEqualByComparingTo("792.89");
	}

	/** 실패를 캐시에 쓰면 "수신 끊김" 이 "값 없음" 으로 바뀐다. 마지막 값을 남겨야 stale 판정이 apiSpec 5.4 대로 된다. */
	@Test
	@DisplayName("한 지수가 실패해도 다른 지수는 부르고, 실패한 지수는 마지막 값을 덮지 않는다")
	void failureKeepsLastValue() {
		IndexEntry last = new IndexEntry(new BigDecimal("2500.00"), new BigDecimal("2490.00"),
			Instant.parse("2026-09-11T05:00:00Z"));
		indexCache.put(MarketIndex.KOSPI, last);
		KisClient client = mock(KisClient.class);
		given(client.indexPrice(any(), eq("0001"))).willThrow(new KisException(KisException.Kind.REJECTED, "값 없음"));
		given(client.indexPrice(any(), eq("1001")))
			.willReturn(new KisIndexQuote(new BigDecimal("793.84"), new BigDecimal("0.95")));

		assertThat(feed(client, true).tick()).isEqualTo(1);

		assertThat(indexCache.get(MarketIndex.KOSPI)).contains(last);
		assertThat(indexCache.get(MarketIndex.KOSDAQ)).isPresent();
	}

	@Test
	@DisplayName("한도 초과면 이번 틱을 접는다 — 뒤 지수를 부르지 않고 예외가 틱 밖으로 새지 않는다")
	void stopsOnRateLimit() {
		KisClient client = mock(KisClient.class);
		given(client.indexPrice(any(), eq("0001"))).willThrow(new KisException(KisException.Kind.RATE_LIMITED, "한도"));

		assertThat(feed(client, true).tick()).isZero();

		verify(client, never()).indexPrice(any(), eq("1001"));
		assertThat(indexCache.getAll()).isEmpty();
	}

	private KisIndexFeed feed(KisClient client, boolean leader) {
		LeaderLock lock = mock(LeaderLock.class);
		given(lock.isLeader()).willReturn(leader);
		KisProperties kisProps = new KisProperties("https://kis.test", List.of(A), Duration.ofSeconds(3), 20,
			Duration.ofSeconds(5), Duration.ofMinutes(5), false);
		return new KisIndexFeed(client, new KisKeyPool(List.of(A)), indexCache, lock, priceProperties, kisProps);
	}
}
