package com.finch.domain.price.feed.kis;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * KIS 실시간 체결가({@code H0STCNT0}) 한 줄을 읽는다. JSON 이 아니라 {@code |} 로 앞머리 3칸, {@code ^} 로 본문 필드를 나눈 평문이다.
 *
 * <pre>
 * 0|H0STCNT0|001|005930^151230^255500^2^3500^ ... ^20260914^20^N^ ...
 * │ │        │   └ 본문. 필드는 위치로 정해진다 (KIS 문서 "국내주식 실시간체결가 [실시간-003]")
 * │ │        └ 이 줄에 담긴 체결 건수. 체결이 몰리면 003 처럼 여러 건이 같은 줄에 이어 붙는다
 * │ └ 메시지 종류
 * └ 암호화 여부. 체결가는 항상 0 (호가·체결통보가 1)
 * </pre>
 *
 * <b>필드 수를 고정하지 않는다.</b> 2026-09-14 KRX 애프터마켓 도입으로 맨 끝에 {@code MARKET_CLS_CODE} 가 붙었듯 KIS 는 뒤에 필드를
 * 더한다. 그래서 "본문 필드 수 ÷ 건수" 로 한 건의 길이를 구하고, 우리가 쓰는 마지막 자리({@link #TRHT_YN})까지만 있으면 받는다.
 * 나누어떨어지지 않거나 짧으면 통째로 버린다 — 반쪽 시세는 틀린 시세다.
 */
final class KisTradeMessage {

	static final String TR_ID = "H0STCNT0";
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

	// 본문 필드 위치 (0부터). 이름은 KIS 문서의 것이다.
	static final int MKSC_SHRN_ISCD = 0;
	static final int STCK_CNTG_HOUR = 1;
	static final int STCK_PRPR = 2;
	static final int PRDY_VRSS_SIGN = 3;
	static final int PRDY_VRSS = 4;
	static final int STCK_OPRC = 7;
	static final int STCK_HGPR = 8;
	static final int STCK_LWPR = 9;
	static final int ACML_VOL = 13;
	static final int BSOP_DATE = 33;
	static final int TRHT_YN = 35;
	/** 애프터마켓 도입(2026-09-14)으로 추가된 장 구분. 1 프리 · 2 정규 · 3 애프터 · 5 종가. 이 자리가 없는 줄도 받는다. */
	static final int MARKET_CLS_CODE = 46;
	/** 한 건에 최소한 있어야 하는 필드 수 — 거래정지 여부까지. */
	static final int MIN_FIELDS = TRHT_YN + 1;

	private KisTradeMessage() {
	}

	/** 이 줄이 체결가 데이터인가. JSON(등록 응답·PINGPONG)은 {@code {} 로 시작한다. */
	static boolean isTradeLine(String payload) {
		return payload != null && !payload.isEmpty() && payload.charAt(0) != '{';
	}

	/** 읽지 못하면 빈 목록. 예외를 던지지 않는다 — 수신 스레드가 죽으면 세션이 통째로 멈춘다. */
	static List<KisTrade> parse(String payload) {
		if (!isTradeLine(payload)) {
			return List.of();
		}
		String[] head = payload.split("\\|", 4);
		if (head.length < 4 || !TR_ID.equals(head[1])) {
			return List.of();
		}
		int count;
		try {
			count = Integer.parseInt(head[2].trim());
		} catch (NumberFormatException e) {
			return List.of();
		}
		if (count <= 0) {
			return List.of();
		}
		// limit=-1 이라야 끝의 빈 필드가 잘리지 않는다 — MARKET_CLS_CODE 앞의 빈 칸들이 사라지면 자리가 밀린다.
		String[] fields = head[3].split("\\^", -1);
		if (fields.length % count != 0) {
			return List.of();
		}
		int per = fields.length / count;
		if (per < MIN_FIELDS) {
			return List.of();
		}
		List<KisTrade> trades = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			KisTrade trade = one(fields, i * per, per);
			if (trade != null) {
				trades.add(trade);
			}
		}
		return trades;
	}

	private static KisTrade one(String[] f, int base, int per) {
		String code = f[base + MKSC_SHRN_ISCD].trim();
		Long price = parseLong(f[base + STCK_PRPR]);
		if (code.isEmpty() || price == null || price <= 0) {
			return null;
		}
		Long diff = parseLong(f[base + PRDY_VRSS]);
		Long previousClose = diff == null ? null : price - signed(diff, f[base + PRDY_VRSS_SIGN]);
		LocalDate date = parseDate(f[base + BSOP_DATE]);
		LocalTime time = parseTime(f[base + STCK_CNTG_HOUR]);
		String marketCls = per > MARKET_CLS_CODE ? f[base + MARKET_CLS_CODE].trim() : "";
		return new KisTrade(code, date, time, price, previousClose, positiveOrNull(f[base + STCK_OPRC]),
			positiveOrNull(f[base + STCK_HGPR]), positiveOrNull(f[base + STCK_LWPR]), volumeOrNull(f[base + ACML_VOL]),
			"Y".equalsIgnoreCase(f[base + TRHT_YN].trim()), marketCls.isEmpty() ? null : marketCls);
	}

	/** 대비부호 1·2 상승, 3 보합, 4·5 하락 — {@code KisClient.signed} 와 같은 규칙. 모르면 값의 부호를 그대로 믿는다. */
	private static long signed(long value, String sign) {
		return switch (sign == null ? "" : sign.trim()) {
			case "1", "2" -> Math.abs(value);
			case "3" -> 0L;
			case "4", "5" -> -Math.abs(value);
			default -> value;
		};
	}

	private static Long parseLong(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 시가·고가·저가는 0 이 "아직 없다" 다 — {@code KisClient.parseLongOrNull} 과 같은 이유로 null 로 둔다. */
	private static Long positiveOrNull(String value) {
		Long parsed = parseLong(value);
		return parsed == null || parsed <= 0 ? null : parsed;
	}

	/** 거래량은 0 이 정상값이라 버리지 않는다. */
	private static Long volumeOrNull(String value) {
		Long parsed = parseLong(value);
		return parsed == null || parsed < 0 ? null : parsed;
	}

	private static LocalDate parseDate(String value) {
		try {
			return value == null || value.isBlank() ? null : LocalDate.parse(value.trim(), DATE);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static LocalTime parseTime(String value) {
		try {
			return value == null || value.isBlank() ? null : LocalTime.parse(value.trim(), TIME);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/**
	 * 체결 한 건에서 우리가 쓰는 값. {@code previousClose} 는 현재가 − 부호 붙인 전일대비로 만든다 — 실시간 메시지에는 기준가 필드가 없다.
	 *
	 * @param tradeDate  영업일자. 캐시의 {@code sessionDate} 다. 없으면 수신 시각의 KST 날짜를 쓴다.
	 * @param tradeTime  체결 시각(KST). 로그용이다 — {@code asOf} 는 수신 시각이다 ({@code PriceEntry.asOf} 주석).
	 * @param suspended  {@code TRHT_YN=Y}. 사유는 실시간에 없다.
	 * @param marketCls  장 구분(1 프리 · 2 정규 · 3 애프터 · 5 종가). 옛 형식이면 null.
	 */
	record KisTrade(String stockCode, LocalDate tradeDate, LocalTime tradeTime, long currentPrice, Long previousClose,
		Long sessionOpen, Long sessionHigh, Long sessionLow, Long sessionVolume, boolean suspended, String marketCls) {
	}
}
