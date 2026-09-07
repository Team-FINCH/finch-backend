package com.finch.domain.price.feed.kis;

/**
 * 현재가 응답({@code FHKST01010100})에서 우리가 쓰는 값만. 캐시({@code PriceEntry})와 종목 갱신({@code PriceObservedEvent})이 나눠 갖는다.
 *
 * @param previousClose   KIS 기준가({@code stck_sdpr}). S5 마스터 동기화가 넣는 값과 같은 출처라 등락 계산이 일관된다.
 * @param suspended       거래정지. {@code temp_stop_yn=Y} 또는 종목상태 {@code 58}(거래정지).
 * @param suspendedReason 종목상태 코드를 사람이 읽는 말로. 없으면 null.
 */
public record KisQuote(long currentPrice, Long previousClose, boolean suspended, String suspendedReason) {
}
