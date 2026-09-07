package com.finch.domain.stock.port;

import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * {@link CandleSourcePort} 의 기본 구현 — 아무것도 모른다. {@code provider=fake} 인 로컬·테스트가 쓴다.
 * KIS 어댑터가 빈으로 있으면 {@link ConditionalOnMissingBean} 이 이 빈을 만들지 않는다 (S5 의 {@code Empty*Port} 와 같은 장치).
 */
@Component
@ConditionalOnMissingBean(value = CandleSourcePort.class, ignored = EmptyCandleSourcePort.class)
public class EmptyCandleSourcePort implements CandleSourcePort {

	@Override
	public List<CandleData> dailyCandles(String stockCode, LocalDate from, LocalDate to) {
		return List.of();
	}
}
