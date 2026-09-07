package com.finch.domain.stock.dto.request;

import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.exception.CustomException;
import java.util.Map;

/**
 * 캔들 기간 (apiSpec 5.3 {@code period}). 값이 {@code 1M}·{@code 3M}·{@code 1Y} 라 자바 enum 이름이 될 수 없어 {@link #from} 으로 읽는다.
 * <p>
 * 열거값 밖은 {@code INVALID_REQUEST} 이고 detail 은 {@code {period: 사유}} — 다른 enum 파라미터의 타입 불일치와 같은 모양이다
 * ({@code GlobalExceptionHandler.handleTypeMismatch}). 프론트가 두 경우를 구분할 이유가 없다.
 * <p>
 * 일봉만이다 ({@code interval=DAY}). 분봉은 S0-4 확장 범위.
 */
public enum CandlePeriod {

	ONE_MONTH("1M", 30),
	THREE_MONTHS("3M", 90),
	ONE_YEAR("1Y", 365);

	private final String value;
	private final int days;

	CandlePeriod(String value, int days) {
		this.value = value;
		this.days = days;
	}

	public static CandlePeriod from(String raw) {
		for (CandlePeriod period : values()) {
			if (period.value.equals(raw)) {
				return period;
			}
		}
		throw new CustomException(GeneralErrorCode.INVALID_REQUEST, Map.of("period", "형식이 올바르지 않습니다"));
	}

	/** 응답의 {@code period} 문자열. */
	public String value() {
		return value;
	}

	/** 오늘 기준으로 거슬러 올라갈 달력일 수. 영업일이 아니라 달력일이다 — 1M 은 "최근 30일" 이다. */
	public int days() {
		return days;
	}
}
