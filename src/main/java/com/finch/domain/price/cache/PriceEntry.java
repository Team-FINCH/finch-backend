package com.finch.domain.price.cache;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 시세 캐시에 담기는 값. <b>이 스키마가 공급자 둘의 계약이다</b> — Fake(S7)와 KIS(S10)가 같은 모양으로 채우고,
 * 소비 측은 어느 쪽이 채웠는지 모른다 (apiSpec 5.6 "두 경로의 시세 페이로드 스키마는 동일하다").
 * <p>
 * <b>등락 금액·등락률을 담지 않는다.</b> 둘은 {@code currentPrice} 와 {@code previousClose} 에서 나오는 파생값이라
 * 함께 저장하면 계산이 쓰는 쪽과 넣는 쪽 두 군데가 되고, 반올림 규칙을 고칠 때 캐시에 남은 옛 값과 갈라진다.
 * 읽을 때 {@code PriceMath} 가 한 곳에서 계산한다.
 * <p>
 * <b>당일 봉({@code session*})은 파생값이 아니라서 담는다.</b> 시가·고가·저가는 현재가에서 계산할 수 없고 하루치 관측이
 * 있어야 나온다. KIS 는 현재가 응답에 이 넷을 함께 주므로 그대로 옮기고, Fake 는 자기가 만든 값을 누적한다.
 * 차트의 <b>진행 중 봉</b>이 이 값으로 그려진다 (apiSpec 5.3) — 저장하지 않고 응답을 만들 때마다 얹으므로
 * 미완성 봉이 {@code daily_candle} 에 굳지 않는다 ({@code CandleSyncService.backfillIfEmpty} 주석).
 *
 * @param previousClose 등락의 기준. KIS 는 현재가 응답에 실려 오는 전일 종가를 넣고, Fake 는 그 종목의 첫 값을 넣는다.
 *                      모르면 null 이고 그때는 등락 둘 다 null 이다.
 * @param asOf          이 값을 받은 시각. {@code stale} 판정의 기준이고 응답에도 그대로 나간다.
 * @param sessionDate   당일 봉이 속한 <b>거래일(KST)</b>. 캐시 키에 만료가 없어 값이 자정을 넘겨 남으므로, 이 날짜가
 *                      바뀌면 공급자가 시가·고가·저가를 새로 시작한다. 모르면 null 이고 그때는 진행 중 봉을 그리지 않는다.
 * @param sessionOpen   당일 시가. 장중에도 변하지 않는다.
 * @param sessionHigh   당일 고가. 지금까지의 최대.
 * @param sessionLow    당일 저가. 지금까지의 최소.
 * @param sessionVolume 당일 누적 거래량. Fake 는 0 이다 — 만들어 낼 근거가 없다.
 */
public record PriceEntry(long currentPrice, Long previousClose, Instant asOf, LocalDate sessionDate,
	Long sessionOpen, Long sessionHigh, Long sessionLow, Long sessionVolume) {

	/** 당일 봉 없이 현재가만. 옛 캐시 값을 읽을 때와 봉을 모르는 공급자용이다. */
	public PriceEntry(long currentPrice, Long previousClose, Instant asOf) {
		this(currentPrice, previousClose, asOf, null, null, null, null, null);
	}

	/** 진행 중 봉을 그릴 수 있는가. 넷 중 하나라도 없으면 그리지 않는다 — 반쪽 봉은 틀린 봉이다. */
	public boolean hasSessionBar() {
		return sessionDate != null && sessionOpen != null && sessionHigh != null && sessionLow != null;
	}
}
