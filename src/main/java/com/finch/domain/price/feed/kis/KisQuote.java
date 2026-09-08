package com.finch.domain.price.feed.kis;

/**
 * 현재가 응답({@code FHKST01010100})에서 우리가 쓰는 값만. 캐시({@code PriceEntry})와 종목 갱신({@code PriceObservedEvent})이 나눠 갖는다.
 *
 * @param previousClose   KIS 기준가({@code stck_sdpr}). S5 마스터 동기화가 넣는 값과 같은 출처라 등락 계산이 일관된다.
 * @param suspended       거래정지. {@code temp_stop_yn=Y} 또는 종목상태 {@code 58}(거래정지).
 * @param suspendedReason 종목상태 코드를 사람이 읽는 말로. 없으면 null.
 * @param sessionOpen     당일 시가({@code stck_oprc}). 장 전이거나 거래가 없으면 0 이 와서 null 로 둔다.
 * @param sessionHigh     당일 고가({@code stck_hgpr}).
 * @param sessionLow      당일 저가({@code stck_lwpr}).
 * @param sessionVolume   당일 누적 거래량({@code acml_vol}).
 */
public record KisQuote(long currentPrice, Long previousClose, boolean suspended, String suspendedReason,
	Long sessionOpen, Long sessionHigh, Long sessionLow, Long sessionVolume) {

	/** 당일 봉을 모르는 응답. 시가·고가·저가가 0 으로 오는 장 전이 여기다 — 그때는 진행 중 봉을 그리지 않는다. */
	public KisQuote(long currentPrice, Long previousClose, boolean suspended, String suspendedReason) {
		this(currentPrice, previousClose, suspended, suspendedReason, null, null, null, null);
	}
}
