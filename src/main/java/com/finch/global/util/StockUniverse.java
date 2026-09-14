package com.finch.global.util;

import com.finch.global.config.FinchProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 서비스 종목 범위 판정 ({@code finch.universe}). <b>판정하는 곳을 여기 하나로 둔다</b> — 검색·상세·시세·주문·관심·AI 중계가
 * 같은 답을 봐야 한다 ({@code MarketClock} 과 같은 이유).
 * <p>
 * {@code global/util} 에 두는 이유 — 범위를 묻는 쪽이 stock(1층)만이 아니라 price(1층)·ai(5층)도 있다. stock 에 두면 price 가
 * 같은 층을 참조하게 된다 (backConvention 2.4 규칙 2). 설정에서 나온 값 하나를 들고 "포함되나" 만 답하므로 도메인 지식이 없다.
 * <p>
 * 범위가 꺼져 있으면({@code enabled=false}) 전부 포함이다 — 호출자는 켜졌는지 몰라도 된다.
 */
@Component
public class StockUniverse {

	private final boolean restricted;
	private final Set<String> codes;

	@Autowired
	public StockUniverse(FinchProperties properties) {
		this(properties.universe());
	}

	/** 설정 조각만으로 만든다. 테스트가 쓴다. */
	public StockUniverse(FinchProperties.Universe universe) {
		this.restricted = universe.enabled();
		this.codes = Set.copyOf(universe.codes());
	}

	/** 범위 없음. 범위와 무관한 테스트가 쓴다. */
	public static StockUniverse unrestricted() {
		return new StockUniverse(FinchProperties.Universe.unrestricted());
	}

	/** 범위가 켜져 있는가. 검색처럼 "범위 없음" 과 "범위 안" 의 쿼리가 다른 곳이 본다. */
	public boolean restricted() {
		return restricted;
	}

	/** 범위 안인가. 꺼져 있으면 언제나 true. */
	public boolean contains(String stockCode) {
		return !restricted || codes.contains(stockCode);
	}

	/** 범위 안의 것만, 순서를 지켜. 꺼져 있으면 그대로 돌려준다. */
	public List<String> filter(Collection<String> stockCodes) {
		if (!restricted) {
			return List.copyOf(stockCodes);
		}
		List<String> kept = new ArrayList<>(stockCodes.size());
		for (String code : stockCodes) {
			if (codes.contains(code)) {
				kept.add(code);
			}
		}
		return kept;
	}

	/** 범위 목록. 꺼져 있으면 빈 목록이다 — "전 종목" 을 나열할 수 없다. */
	public List<String> codes() {
		return List.copyOf(codes);
	}
}
