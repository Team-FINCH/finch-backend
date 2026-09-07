package com.finch.domain.price;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 등락 계산. <b>공식이 있는 곳은 여기 하나다</b> — 단건·다건 API, 검색·상세·관심 목록이 전부 이 결과를 쓴다.
 * 캐시에는 현재가와 기준가만 담고 등락은 읽을 때 계산한다 ({@code PriceEntry} 주석).
 * <p>
 * 등락률은 소수 둘째 자리 HALF_UP 이다 (backConvention 6장). 부동소수로 계산하지 않는다.
 */
public final class PriceMath {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private PriceMath() {
	}

	/** 기준가를 모르면 null. 화면은 등락 영역을 비운다. */
	public static Long changeAmount(long currentPrice, Long previousClose) {
		return previousClose == null ? null : currentPrice - previousClose;
	}

	/** 기준가가 없거나 0 이면 null — 0 으로 나눌 수 없고, 0% 로 답하면 실제로 변동이 없는 종목과 구분되지 않는다. */
	public static BigDecimal changeRate(long currentPrice, Long previousClose) {
		if (previousClose == null || previousClose == 0) {
			return null;
		}
		return BigDecimal.valueOf(currentPrice - previousClose)
			.multiply(HUNDRED)
			.divide(BigDecimal.valueOf(previousClose), 2, RoundingMode.HALF_UP);
	}
}
