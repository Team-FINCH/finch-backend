package com.finch.domain.price.feed.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.cache.InterestRegistry;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.domain.price.event.PriceObservedEvent;
import com.finch.domain.price.feed.RealtimeCoverage;
import com.finch.global.lock.LeaderLock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;

/**
 * 폴링 한 틱이 무엇을 하는지 — 관심 종목만 부르고, 캐시를 채우고, 바뀐 종목만 stock 에 알리고, 키 수만큼 나눠 부른다.
 * KIS 클라이언트는 목이고 캐시·관심 신호는 컨테이너의 진짜 Redis 다. 리더 락도 목이다 — 컨텍스트의 진짜 빈이 락을 쥐고 있어
 * 새 인스턴스는 리더가 될 수 없다.
 * <p>
 * 관심 목록은 Redis 에 30초 남으므로 다른 테스트가 남긴 종목이 같이 순회된다. 그래서 개수는 "이상" 으로, 내용은 이 테스트의 종목으로만 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KisPollingFeedTest {

	private static final AtomicLong CODE_SEQ = new AtomicLong(800_000L);
	private static final KisCredential A = new KisCredential("ka", "sa", "a");
	private static final KisCredential B = new KisCredential("kb", "sb", "b");

	@Autowired
	private PriceCache priceCache;

	@Autowired
	private InterestRegistry interestRegistry;

	private final List<Object> published = new ArrayList<>();

	@Test
	@DisplayName("리더가 아니면 아무것도 부르지 않는다")
	void nonLeaderDoesNothing() {
		KisClient client = mock(KisClient.class);
		KisPollingFeed feed = feed(client, List.of(A), false);
		interestRegistry.touch(List.of(newCode()));

		assertThat(feed.tick()).isZero();
		verifyNoInteractions(client);
	}

	@Test
	@DisplayName("관심 종목만 부르고 캐시에 현재가·기준가를 넣는다. 바뀐 종목만 PriceObservedEvent 를 낸다")
	void fillsCacheAndPublishesChanges() {
		String code = newCode();
		KisClient client = mock(KisClient.class);
		given(client.currentPrice(any(), any())).willReturn(new KisQuote(1, 1L, false, null));
		given(client.currentPrice(eq(A), eq(code)))
			.willReturn(new KisQuote(73_500, 74_400L, false, null))
			.willReturn(new KisQuote(73_600, 74_400L, false, null))
			.willReturn(new KisQuote(73_600, 74_400L, true, "거래정지"));
		KisPollingFeed feed = feed(client, List.of(A), true);
		interestRegistry.touch(List.of(code));

		assertThat(feed.tick()).isGreaterThanOrEqualTo(1);
		PriceEntry entry = priceCache.get(code).orElseThrow();
		assertThat(entry.currentPrice()).isEqualTo(73_500);
		assertThat(entry.previousClose()).isEqualTo(74_400L);
		assertThat(publishedFor(code)).containsExactly(new PriceObservedEvent(code, 74_400L, false, null));

		// 현재가만 바뀌었다 — 캐시는 갱신되지만 stock 이 알 일은 없다.
		feed.tick();
		assertThat(priceCache.get(code).orElseThrow().currentPrice()).isEqualTo(73_600);
		assertThat(publishedFor(code)).hasSize(1);

		// 거래정지로 바뀌었다 — 알린다.
		feed.tick();
		assertThat(publishedFor(code)).hasSize(2);
		assertThat(publishedFor(code).getLast()).isEqualTo(new PriceObservedEvent(code, 74_400L, true, "거래정지"));
	}

	@Test
	@DisplayName("키가 둘이면 종목을 나눠 각 키로 부른다 — 수용량이 키 수만큼 는다")
	void splitsAcrossKeys() {
		String c1 = newCode();
		String c2 = newCode();
		KisClient client = mock(KisClient.class);
		given(client.currentPrice(any(), any())).willReturn(new KisQuote(1_000, 1_000L, false, null));
		KisPollingFeed feed = feed(client, List.of(A, B), true);
		interestRegistry.touch(List.of(c1, c2));

		assertThat(feed.tick()).isGreaterThanOrEqualTo(2);

		verify(client, atLeastOnce()).currentPrice(eq(A), any());
		verify(client, atLeastOnce()).currentPrice(eq(B), any());
		assertThat(priceCache.get(c1)).isPresent();
		assertThat(priceCache.get(c2)).isPresent();
	}

	@Test
	@DisplayName("한 종목이 실패해도 나머지는 계속하고, 한도 초과는 그 키의 이번 순회를 접는다")
	void continuesOnErrorButStopsOnRateLimit() {
		String failing = newCode();
		String ok = newCode();
		String afterLimit = newCode();
		KisClient client = mock(KisClient.class);
		given(client.currentPrice(any(), any())).willReturn(new KisQuote(1, 1L, false, null));
		given(client.currentPrice(eq(A), eq(failing)))
			.willThrow(new KisException(KisException.Kind.REJECTED, "조회 불가"));
		given(client.currentPrice(eq(A), eq(ok))).willReturn(new KisQuote(1_000, 1_000L, false, null));
		given(client.currentPrice(eq(A), eq(afterLimit)))
			.willThrow(new KisException(KisException.Kind.RATE_LIMITED, "한도"));
		KisPollingFeed feed = feed(client, List.of(A), true);
		// 관심 목록은 SET 이라 순서가 없다. 어떤 순서든 성립하는 것만 본다.
		interestRegistry.touch(List.of(failing, ok));

		assertThat(feed.tick()).isGreaterThanOrEqualTo(1);
		assertThat(priceCache.get(ok)).isPresent();
		assertThat(priceCache.get(failing)).isEmpty();

		// 한도 초과 종목은 캐시에 들어가지 않고, 그 뒤 종목은 이번 틱에서 부르지 않는다(로그만). 예외가 틱 밖으로 새지 않는다.
		interestRegistry.touch(List.of(afterLimit));
		assertThat(feed.tick()).isGreaterThanOrEqualTo(0);
		assertThat(priceCache.get(afterLimit)).isEmpty();
	}

	private List<PriceObservedEvent> publishedFor(String code) {
		return published.stream().filter(PriceObservedEvent.class::isInstance).map(PriceObservedEvent.class::cast)
			.filter(e -> e.stockCode().equals(code)).toList();
	}

	@Test
	@DisplayName("실시간 티어가 맡고 있는 종목은 순회하지 않는다 — 커버리지가 비면(웹소켓 끊김) 다시 돈다")
	void skipsRealtimeCoveredCodes() {
		String covered = newCode();
		String polled = newCode();
		KisClient client = mock(KisClient.class);
		given(client.currentPrice(any(), any())).willReturn(new KisQuote(1_000, 1_000L, false, null));
		Set<String> coverage = new HashSet<>(Set.of(covered));
		KisPollingFeed feed = feed(client, List.of(A), true, () -> coverage);
		interestRegistry.touch(List.of(covered, polled));

		feed.tick();
		verify(client, never()).currentPrice(eq(A), eq(covered));
		verify(client).currentPrice(eq(A), eq(polled));
		assertThat(priceCache.get(covered)).isEmpty();

		coverage.clear();
		feed.tick();
		verify(client).currentPrice(eq(A), eq(covered));
	}

	private KisPollingFeed feed(KisClient client, List<KisCredential> keys, boolean leader) {
		return feed(client, keys, leader, RealtimeCoverage.NONE);
	}

	private KisPollingFeed feed(KisClient client, List<KisCredential> keys, boolean leader, RealtimeCoverage coverage) {
		LeaderLock lock = mock(LeaderLock.class);
		given(lock.isLeader()).willReturn(leader);
		ApplicationEventPublisher publisher = published::add;
		KisProperties props = new KisProperties("https://kis.test", keys, Duration.ofSeconds(3), 20,
			Duration.ofSeconds(5), Duration.ofMinutes(5), false);
		return new KisPollingFeed(client, new KisKeyPool(keys), priceCache, interestRegistry, lock, publisher, props,
			coverage);
	}

	private static String newCode() {
		return String.valueOf(CODE_SEQ.incrementAndGet());
	}
}
