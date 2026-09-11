package com.finch.domain.price.service;

import com.finch.domain.price.MarketIndex;
import com.finch.domain.price.PriceMath;
import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.IndexCache;
import com.finch.domain.price.cache.IndexEntry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 시장 지수 조회 (apiSpec 5.7). {@link PriceQueryService} 와 같은 일을 지수에 대해 한다 — 캐시를 읽고 {@code stale} 을 판정한다.
 * <p>
 * 다른 점이 둘이다. <b>관심 신호를 남기지 않는다</b> — 지수는 공급자가 상시로 채운다. <b>판정 시간이 다르다</b> — 수집 주기가 10초라
 * 종목 시세의 {@code stale-after}(10초)를 쓰면 지수가 상시 "지연" 이 된다. {@code index.stale-after}(60초)를 쓴다.
 */
@Service
public class IndexQueryService {

	private final IndexCache indexCache;
	private final PriceProperties properties;
	private final Clock clock;

	@Autowired
	public IndexQueryService(IndexCache indexCache, PriceProperties properties) {
		this(indexCache, properties, Clock.systemUTC());
	}

	/** 시각을 고정해 {@code stale} 경계를 확인하려는 테스트가 쓴다 ({@link PriceQueryService} 와 같은 이유). */
	public IndexQueryService(IndexCache indexCache, PriceProperties properties, Clock clock) {
		this.indexCache = indexCache;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 전 지수. <b>{@link MarketIndex} 선언 순서대로 언제나 전부 담긴다</b> — 캐시에 없는 지수도 "값 없음" 으로 들어간다.
	 * 프론트는 배열 길이나 순서를 검사하지 않는다 (apiSpec 5.7).
	 */
	public List<IndexSnapshot> latestAll() {
		Map<MarketIndex, IndexEntry> entries = indexCache.getAll();
		Instant now = Instant.now(clock);
		return Arrays.stream(MarketIndex.values())
			.map(index -> toSnapshot(index, entries.get(index), now))
			.toList();
	}

	/** apiSpec 5.4 의 세 상태를 지수에 대해 만든다. 값 없음 · 수신 끊김(마지막 값 유지) · 정상. */
	private IndexSnapshot toSnapshot(MarketIndex index, IndexEntry entry, Instant now) {
		if (entry == null) {
			return IndexSnapshot.missing(index);
		}
		boolean stale = now.isAfter(entry.asOf().plus(properties.index().staleAfter()));
		return new IndexSnapshot(index, entry.currentValue(),
			PriceMath.indexChange(entry.currentValue(), entry.previousClose()),
			PriceMath.indexChangeRate(entry.currentValue(), entry.previousClose()),
			entry.asOf(), stale);
	}

	/** 지수 하나의 조회 결과. 값 없음이면 {@code index} 와 {@code stale=true} 만 있다. */
	public record IndexSnapshot(MarketIndex index, BigDecimal currentValue, BigDecimal changeValue,
		BigDecimal changeRate, Instant asOf, boolean stale) {

		public static IndexSnapshot missing(MarketIndex index) {
			return new IndexSnapshot(index, null, null, null, null, true);
		}
	}
}
