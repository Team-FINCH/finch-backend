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
import com.finch.global.config.FinchProperties;
import com.finch.global.lock.LeaderLock;
import com.finch.global.util.MarketClock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

	/** 금요일 14:30 KST — 정규장. 시각을 대지 않는 테스트는 전부 이 시각이다. */
	private static final Instant OPEN_AT = Instant.parse("2026-09-11T05:30:00Z");
	/** 금요일 17:30 KST — 애프터마켓(16:00~20:00). */
	private static final Instant AFTER_AT = Instant.parse("2026-09-11T08:30:00Z");
	/** 토요일 14:30 KST — 휴장. 시간대가 아니라 요일로 닫히는 쪽을 고른다. */
	private static final Instant CLOSED_AT = Instant.parse("2026-09-12T05:30:00Z");

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

	/**
	 * 이슈 #76 — 관심 신호를 보지 않는 피드라 장 시간이 유일한 제동이다. 장 밖에 도는 것을 막는 것이 이 이슈의 요청이었고,
	 * 화면을 전부 닫아도 10초마다 2회가 나가던 것이 이 호출이다.
	 */
	@Test
	@DisplayName("장 밖이면 KIS 를 부르지 않는다 — 리더여도 틱이 0 이고 캐시를 건드리지 않는다")
	void skipsOutsideMarketHours() {
		KisClient client = mock(KisClient.class);

		assertThat(feed(client, true, CLOSED_AT).tick()).isZero();

		verifyNoInteractions(client);
		assertThat(indexCache.getAll()).isEmpty();
	}

	/** 애프터마켓(16:00~20:00)까지는 채운다 — {@code isOpen()} 기준이다. 좁히려면 피드와 조회의 조건을 함께 좁혀야 한다. */
	@Test
	@DisplayName("애프터마켓에는 계속 채운다")
	void fillsDuringAfterMarket() {
		KisClient client = mock(KisClient.class);
		given(client.indexPrice(any(), any()))
			.willReturn(new KisIndexQuote(new BigDecimal("2600.54"), new BigDecimal("2612.85")));

		assertThat(feed(client, true, AFTER_AT).tick()).isEqualTo(2);
	}

	private KisIndexFeed feed(KisClient client, boolean leader) {
		return feed(client, leader, OPEN_AT);
	}

	private KisIndexFeed feed(KisClient client, boolean leader, Instant at) {
		LeaderLock lock = mock(LeaderLock.class);
		given(lock.isLeader()).willReturn(leader);
		KisProperties kisProps = new KisProperties("https://kis.test", List.of(A), Duration.ofSeconds(3), 20,
			Duration.ofSeconds(5), Duration.ofMinutes(5), false);
		return new KisIndexFeed(client, new KisKeyPool(List.of(A)), indexCache, lock, marketClockAt(at),
			priceProperties, kisProps);
	}

	private static MarketClock marketClockAt(Instant instant) {
		FinchProperties finch = new FinchProperties(new FinchProperties.Market(false),
			new FinchProperties.Http(Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(30)),
			new FinchProperties.LeaderLock(Duration.ofSeconds(10), Duration.ofSeconds(3)),
			new FinchProperties.Internal("test-only-internal-token"));
		return new MarketClock(finch, Clock.fixed(instant, ZoneOffset.UTC));
	}
}
