package com.finch.domain.price.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.price.MarketIndex;
import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.IndexCache;
import com.finch.domain.price.cache.IndexEntry;
import com.finch.domain.price.service.IndexQueryService.IndexSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
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
 * 지수의 {@code stale} 3상태(apiSpec 5.7)를 실제 Redis 로 본다. 시각은 고정한다 — {@code PriceQueryServiceTest} 와 같은 이유로
 * 서비스를 주입받지 않고 고정 {@code Clock} 으로 직접 만든다.
 * <p>
 * 지수 키는 둘뿐이라 다른 테스트와 같은 키를 쓴다. 그래서 테스트마다 먼저 지운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IndexQueryServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-11T05:30:00Z");

	@Autowired
	private IndexCache indexCache;

	@Autowired
	private PriceProperties properties;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void clearIndices() {
		redisTemplate.delete(List.of("index:KOSPI", "index:KOSDAQ"));
	}

	@Test
	@DisplayName("정상 — 값이 있고 stale 은 false, 변동폭·등락률은 전일 종가에서 계산해 소수 둘째 자리로 나간다")
	void fresh() {
		indexCache.put(MarketIndex.KOSPI,
			new IndexEntry(new BigDecimal("2600.54"), new BigDecimal("2612.85"), NOW.minusSeconds(5)));

		IndexSnapshot kospi = serviceAt(NOW).latestAll().getFirst();

		assertThat(kospi.index()).isEqualTo(MarketIndex.KOSPI);
		assertThat(kospi.stale()).isFalse();
		assertThat(kospi.currentValue()).isEqualByComparingTo("2600.54");
		assertThat(kospi.changeValue()).isEqualTo(new BigDecimal("-12.31"));
		assertThat(kospi.changeRate()).isEqualTo(new BigDecimal("-0.47"));
		assertThat(kospi.asOf()).isEqualTo(NOW.minusSeconds(5));
	}

	/** 종목 시세의 stale-after(10초)가 아니라 지수용(60초)을 쓴다 — 수집 주기가 10초라 10초로 판정하면 상시 지연이 된다. */
	@Test
	@DisplayName("수신 끊김 — 지수용 판정 시간을 넘으면 마지막 값을 그대로 두고 stale 만 true 다")
	void staleKeepsLastValue() {
		Instant withinIndexWindow = NOW.minus(properties.staleAfter()).minusSeconds(1);
		Instant old = NOW.minus(properties.index().staleAfter()).minusSeconds(1);
		indexCache.put(MarketIndex.KOSPI, new IndexEntry(new BigDecimal("2600.54"), new BigDecimal("2612.85"),
			withinIndexWindow));
		indexCache.put(MarketIndex.KOSDAQ, new IndexEntry(new BigDecimal("793.84"), new BigDecimal("792.89"), old));

		List<IndexSnapshot> snapshots = serviceAt(NOW).latestAll();

		assertThat(snapshots.get(0).stale()).isFalse();
		IndexSnapshot kosdaq = snapshots.get(1);
		assertThat(kosdaq.stale()).isTrue();
		assertThat(kosdaq.currentValue()).isEqualByComparingTo("793.84");
		assertThat(kosdaq.changeValue()).isEqualByComparingTo("0.95");
		assertThat(kosdaq.asOf()).isEqualTo(old);
	}

	@Test
	@DisplayName("값 없음 — 캐시에 없어도 빠지지 않고 셋 다 null 에 stale true 다. 언제나 KOSPI → KOSDAQ 두 개다")
	void missingStillListedInOrder() {
		indexCache.put(MarketIndex.KOSDAQ,
			new IndexEntry(new BigDecimal("793.84"), new BigDecimal("792.89"), NOW.minusSeconds(1)));

		List<IndexSnapshot> snapshots = serviceAt(NOW).latestAll();

		assertThat(snapshots).extracting(IndexSnapshot::index).containsExactly(MarketIndex.KOSPI, MarketIndex.KOSDAQ);
		assertThat(snapshots.getFirst()).isEqualTo(IndexSnapshot.missing(MarketIndex.KOSPI));
		assertThat(snapshots.get(1).stale()).isFalse();
	}

	private IndexQueryService serviceAt(Instant now) {
		return new IndexQueryService(indexCache, properties, Clock.fixed(now, ZoneOffset.UTC));
	}
}
