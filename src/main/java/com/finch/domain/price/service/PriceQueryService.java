package com.finch.domain.price.service;

import com.finch.domain.price.PriceMath;
import com.finch.domain.price.PriceProperties;
import com.finch.domain.price.cache.InterestRegistry;
import com.finch.domain.price.cache.PriceCache;
import com.finch.domain.price.cache.PriceEntry;
import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.global.util.StockUniverse;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 시세 조회. {@link PriceQueryPort} 의 실제 구현이고, <b>S5 가 두었던 {@code EmptyPriceQueryPort} 를 이 MR 에서 지웠다</b> —
 * 남겨 두면 빈이 둘이 되어 기동이 실패한다. 이 MR 이 머지되면 종목 검색·상세·관심 목록·최근 본 종목의 시세가 실제 값이 된다.
 * <p>
 * 하는 일이 둘이다. <b>관심 신호를 남기고</b>, <b>{@code stale} 을 판정한다.</b> 값을 만들지는 않는다 — 캐시를 채우는 것은
 * 공급자의 몫이고 이 서비스는 공급자가 누구인지 모른다.
 * <p>
 * <b>{@code stale} 판정이 여기 한 곳에 있다</b> (apiSpec 5.4 의 세 상태). 단건·다건·검색·관심 목록이 전부 이 메서드를 지나므로
 * 화면마다 다른 기준이 생길 자리가 없다.
 */
@Service
public class PriceQueryService implements PriceQueryPort {

	private final PriceCache priceCache;
	private final InterestRegistry interestRegistry;
	private final PriceProperties properties;
	private final StockUniverse universe;
	private final Clock clock;

	@Autowired
	public PriceQueryService(PriceCache priceCache, InterestRegistry interestRegistry, PriceProperties properties,
		StockUniverse universe) {
		this(priceCache, interestRegistry, properties, universe, Clock.systemUTC());
	}

	/**
	 * 시각을 고정해 {@code stale} 경계를 확인하려는 테스트가 쓴다. 실제 시간을 기다리거나 {@code Thread.sleep} 으로 흉내 내면
	 * 테스트가 느리고 불안정해진다 ({@code MarketClock} 과 같은 이유).
	 */
	public PriceQueryService(PriceCache priceCache, InterestRegistry interestRegistry, PriceProperties properties,
		StockUniverse universe, Clock clock) {
		this.priceCache = priceCache;
		this.interestRegistry = interestRegistry;
		this.properties = properties;
		this.universe = universe;
		this.clock = clock;
	}

	/**
	 * 단건. <b>이 호출도 관심 신호다</b> (apiSpec 5.6 — 묻는 것 자체가 신호이고 별도 구독 엔드포인트가 없다).
	 * 컨트롤러가 아니라 서비스에서 남기는 이유 — 종목 상세·검색·관심 목록도 이 포트를 거치는데, 그 화면들이 보고 있는 종목 역시
	 * 갱신되어야 한다. 컨트롤러에 두면 다건 조회 엔드포인트를 부른 종목만 살아 있게 된다.
	 */
	@Override
	public PriceSnapshot latest(String stockCode) {
		touch(List.of(stockCode));
		return toSnapshot(priceCache.get(stockCode).orElse(null));
	}

	/**
	 * 진행 중인 당일 봉 (apiSpec 5.3). 캐시에 시가·고가·저가가 다 있을 때만 준다.
	 * <p>
	 * <b>관심 신호를 남기지 않는다.</b> 차트는 한 번 그리고 마는 화면이라 그 종목을 순회 대상으로 붙잡아 둘 이유가 없다.
	 * 현재가를 함께 보고 있으면 {@link #latest} 가 이미 신호를 남긴다.
	 * <p>
	 * {@code stale} 을 보지 않는다 — 수신이 끊겨도 마지막으로 받은 당일 봉은 여전히 그날의 값이다. 확정된 봉과 겹치는지는
	 * 부르는 쪽({@code StockService.candles})이 날짜로 가른다.
	 */
	@Override
	public Optional<SessionBar> sessionBar(String stockCode) {
		return priceCache.get(stockCode)
			.filter(PriceEntry::hasSessionBar)
			.map(e -> new SessionBar(e.sessionDate(), e.sessionOpen(), e.sessionHigh(), e.sessionLow(),
				e.currentPrice(), e.sessionVolume() == null ? 0L : e.sessionVolume()));
	}

	/** 다건. 캐시는 {@code MGET} 한 번이다. <b>요청한 모든 코드를 키로 돌려준다</b> — 없는 종목은 "값 없음" 이다 (포트 계약). */
	@Override
	public Map<String, PriceSnapshot> latestAll(Collection<String> stockCodes) {
		List<String> codes = List.copyOf(stockCodes);
		touch(codes);

		Map<String, PriceEntry> entries = priceCache.getAll(codes);
		Map<String, PriceSnapshot> result = new LinkedHashMap<>();
		for (String code : codes) {
			result.put(code, toSnapshot(entries.get(code)));
		}
		return result;
	}

	/**
	 * 관심 신호는 <b>종목 범위 안</b>만 남긴다 ({@link StockUniverse}). 범위 밖 종목은 어느 화면이 물어도 공급자가 채우지 않으므로
	 * 영영 "값 없음" 이다 — KIS REST 예산이 범위 밖으로 새지 않는다. 캐시 읽기는 그대로다: 범위 밖 코드는 캐시에 생기지 않아 결과가 같다.
	 */
	private void touch(List<String> codes) {
		List<String> inUniverse = universe.filter(codes);
		if (!inUniverse.isEmpty()) {
			interestRegistry.touch(inUniverse);
		}
	}

	/**
	 * apiSpec 5.4 의 세 상태를 만드는 곳.
	 * <ul>
	 *   <li><b>값 없음</b> — 캐시에 없다. 전부 null, {@code stale=true}. 아직 공급자가 채우지 않았다는 뜻이고 에러가 아니다.</li>
	 *   <li><b>수신 끊김</b> — {@code now - asOf > stale-after}. <b>마지막 값을 그대로 두고</b> {@code stale=true}. 화면은
	 *       "시세 지연" 을 띄우되 가격은 계속 보여준다.</li>
	 *   <li><b>정상</b> — {@code stale=false}.</li>
	 * </ul>
	 * 등락은 저장하지 않고 {@link PriceMath} 가 계산한다.
	 */
	private PriceSnapshot toSnapshot(PriceEntry entry) {
		if (entry == null) {
			return PriceSnapshot.missing();
		}
		boolean stale = Instant.now(clock).isAfter(entry.asOf().plus(properties.staleAfter()));
		return new PriceSnapshot(entry.currentPrice(),
			PriceMath.changeAmount(entry.currentPrice(), entry.previousClose()),
			PriceMath.changeRate(entry.currentPrice(), entry.previousClose()),
			entry.asOf(), stale);
	}
}
