package com.finch.domain.price.feed.kis;

import com.finch.domain.stock.port.CandleSourcePort;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link CandleSourcePort} 의 실제 구현. stock(1층)이 선언하고 price(1층)가 구현한다 — {@code PriceQueryService} 와 같은 뒤집기.
 * {@code provider=kis} 일 때만 빈이 되어 {@code EmptyCandleSourcePort} 를 밀어낸다.
 * <p>
 * 키는 풀에서 돌아가며 받는다 ({@link KisKeyPool#next()}). 일봉 백필은 단발 호출이라 폴링처럼 종목을 키 수로 나눌 것이 없고,
 * 돌아가며 쓰면 어느 한 키의 초당 한도에 폴링과 백필이 함께 몰리는 일이 줄어든다.
 */
@Component
@ConditionalOnProperty(name = "finch.price.provider", havingValue = "kis")
@RequiredArgsConstructor
public class KisCandleSourceAdapter implements CandleSourcePort {

	private final KisClient client;
	private final KisKeyPool keyPool;

	@Override
	public List<CandleData> dailyCandles(String stockCode, LocalDate from, LocalDate to) {
		return client.dailyCandles(keyPool.next(), stockCode, from, to).stream()
			.map(c -> new CandleData(c.tradeDate(), c.open(), c.high(), c.low(), c.close(), c.volume()))
			.toList();
	}
}
