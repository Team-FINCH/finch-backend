package com.finch.domain.stock.dto.request;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 봉 하나의 크기 (apiSpec 5.3 {@code interval}). {@link CandlePeriod} 와 두 축이다 — {@code period} 는 <b>얼마나 볼지</b>,
 * 이쪽은 <b>봉 하나가 얼마인지</b>다. 둘은 독립이라 {@code 1Y+DAY}(일봉 250개)도 {@code 3Y+MONTH}(월봉 36개)도 된다.
 * <p>
 * <b>{@code from} 이 없다.</b> {@link CandlePeriod} 는 {@code 1M} 이 자바 식별자가 될 수 없어 직접 읽지만, 이쪽 값은 열거값
 * 이름 그대로라 스프링의 기본 변환기가 읽는다. 열거값 밖은 {@code MethodArgumentTypeMismatchException} 이 되고
 * {@code GlobalExceptionHandler.handleTypeMismatch} 가 {@code INVALID_REQUEST} + {@code {interval: 사유}} 로 답한다 —
 * {@code CandlePeriod.from} 이 손으로 만드는 것과 <b>같은 모양</b>이다. 프론트가 두 경우를 구분할 이유가 없다.
 * <p>
 * <b>저장은 언제나 일봉이다.</b> {@code daily_candle} 하나를 읽어 {@link #bucketOf} 로 묶는다 (erd.md §2.8). 주봉·월봉
 * 테이블을 따로 두지 않는 이유 — 원본이 일봉이라 언제든 다시 묶을 수 있고, 두면 일일 배치가 갱신할 곳이 셋이 된다.
 * 분봉은 이 열거값이 아니라 {@code minute_candle} 을 새로 만드는 확장이다 (S0-4).
 */
public enum CandleInterval {

	DAY,
	WEEK,
	MONTH;

	/** 응답의 {@code interval} 문자열. 열거값 이름 그대로다. */
	public String value() {
		return name();
	}

	/**
	 * 이 날짜가 속한 구간의 대표값. 같은 값이 나오는 날들이 봉 하나가 된다.
	 * <p>
	 * 주는 <b>월요일 시작</b>이다 — {@code with(DayOfWeek.MONDAY)} 는 ISO 주(월~일) 안에서 월요일로 옮긴다. 달은 1일 시작이다.
	 * 기준 시간대는 호출자가 이미 KST 로 맞춘 {@code trade_date} 다 — 여기서 시간대를 다시 다루지 않는다.
	 * <p>
	 * 이 값은 <b>묶는 열쇠일 뿐 응답에 나가지 않는다.</b> 응답의 {@code date} 는 구간의 첫 <b>거래일</b>이다 (apiSpec 5.3).
	 * 그 주 월요일이 휴장이면 월요일이 아니라 실제로 거래가 있었던 첫날이 나간다.
	 */
	public LocalDate bucketOf(LocalDate date) {
		return switch (this) {
			case DAY -> date;
			case WEEK -> date.with(DayOfWeek.MONDAY);
			case MONTH -> date.withDayOfMonth(1);
		};
	}
}
