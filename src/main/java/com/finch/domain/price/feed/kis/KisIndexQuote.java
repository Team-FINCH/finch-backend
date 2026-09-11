package com.finch.domain.price.feed.kis;

import java.math.BigDecimal;

/**
 * 업종 현재지수 응답({@code FHPUP02100000})에서 쓰는 값만. 둘 다 소수 둘째 자리다.
 *
 * @param currentValue 현재 지수({@code bstp_nmix_prpr}). 언제나 양수다 — 0 이 오면 {@code KisClient} 가 거절로 바꾼다.
 * @param changeValue  전일 대비({@code bstp_nmix_prdy_vrss}). <b>부호가 붙어 있다</b> — 대비부호({@code prdy_vrss_sign})로
 *                     {@code KisClient} 가 붙인다.
 */
public record KisIndexQuote(BigDecimal currentValue, BigDecimal changeValue) {

	/** 전일 종가. KIS 가 따로 주지 않아 되돌려 계산한다 — 캐시는 등락이 아니라 기준값을 담는다({@code IndexEntry}). */
	public BigDecimal previousClose() {
		return currentValue.subtract(changeValue);
	}
}
