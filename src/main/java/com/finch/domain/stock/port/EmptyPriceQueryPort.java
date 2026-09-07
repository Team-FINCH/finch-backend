package com.finch.domain.stock.port;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link PriceQueryPort} 의 기본 구현. <b>price 도메인(S7)이 생기기 전까지</b>만 쓰인다. 전부 "값 없음"이다 (apiSpec 5.4 셋째 행).
 * <p>
 * ⚠️ <b>S7 이 이 파일을 지운다.</b> 지우지 않고 자기 구현을 추가하면 빈이 둘이 되어 기동이 실패한다 — 조용히 null 시세를 쓰는
 * 것보다 낫다. {@code @ConditionalOnMissingBean} 으로 자동 교체하지 않는 이유는 {@code EmptyValuationPort} 에 있다
 * (컴포넌트 스캔 시점에 조건이 평가되어 빈이 통째로 빠진다).
 */
@Component
public class EmptyPriceQueryPort implements PriceQueryPort {

	@Override
	public PriceSnapshot latest(String stockCode) {
		return PriceSnapshot.missing();
	}

	@Override
	public Map<String, PriceSnapshot> latestAll(Collection<String> stockCodes) {
		Map<String, PriceSnapshot> result = new LinkedHashMap<>();
		for (String code : stockCodes) {
			result.put(code, PriceSnapshot.missing());
		}
		return result;
	}
}
