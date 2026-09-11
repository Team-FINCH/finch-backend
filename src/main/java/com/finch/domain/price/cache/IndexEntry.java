package com.finch.domain.price.cache;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 지수 캐시에 담기는 값 (apiSpec 5.7). {@link PriceEntry} 와 같은 원칙이다 — <b>등락을 담지 않고</b> 기준값만 담아
 * 읽을 때 {@code PriceMath} 가 계산한다.
 * <p>
 * 금액이 아니라서 {@code long} 이 아니라 {@code BigDecimal} 이다. 지수는 소수 둘째 자리까지 의미가 있고, {@code double} 로 들면
 * {@code 2600.54} 가 {@code 2600.5399999} 로 나갈 수 있다.
 *
 * @param currentValue  현재 지수.
 * @param previousClose 전일 종가. 등락의 기준이다. KIS 는 전일 종가를 따로 주지 않아 {@code 현재 − 전일대비} 로 되돌려 넣는다.
 * @param asOf          이 값을 받은 시각. {@code stale} 판정의 기준이다.
 */
public record IndexEntry(BigDecimal currentValue, BigDecimal previousClose, Instant asOf) {
}
