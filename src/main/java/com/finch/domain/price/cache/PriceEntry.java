package com.finch.domain.price.cache;

import java.time.Instant;

/**
 * 시세 캐시에 담기는 값. <b>이 스키마가 공급자 둘의 계약이다</b> — Fake(S7)와 KIS(S10)가 같은 모양으로 채우고,
 * 소비 측은 어느 쪽이 채웠는지 모른다 (apiSpec 5.6 "두 경로의 시세 페이로드 스키마는 동일하다").
 * <p>
 * <b>등락 금액·등락률을 담지 않는다.</b> 둘은 {@code currentPrice} 와 {@code previousClose} 에서 나오는 파생값이라
 * 함께 저장하면 계산이 쓰는 쪽과 넣는 쪽 두 군데가 되고, 반올림 규칙을 고칠 때 캐시에 남은 옛 값과 갈라진다.
 * 읽을 때 {@code PriceMath} 가 한 곳에서 계산한다.
 *
 * @param previousClose 등락의 기준. KIS 는 현재가 응답에 실려 오는 전일 종가를 넣고, Fake 는 그 종목의 첫 값을 넣는다.
 *                      모르면 null 이고 그때는 등락 둘 다 null 이다.
 * @param asOf          이 값을 받은 시각. {@code stale} 판정의 기준이고 응답에도 그대로 나간다.
 */
public record PriceEntry(long currentPrice, Long previousClose, Instant asOf) {
}
