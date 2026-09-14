package com.finch.domain.price.feed.kis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 체결가 평문 한 줄을 읽는 규칙. 고정하는 것: 필드 위치 · 대비부호로 전일종가를 만드는 것 · 여러 건이 한 줄에 올 때 나누는 것 ·
 * 필드가 뒤에 추가돼도(MARKET_CLS_CODE) 읽히는 것 · 짧거나 어긋난 줄은 통째로 버리는 것.
 */
class KisTradeMessageTest {

	/** 46 필드(애프터마켓 도입 전 형식). 삼성전자 15:12:30 체결, 255,500 원, 전일 대비 +3,500. */
	private static final String BODY_46 = "005930^151230^255500^2^3500^1.39^254812^252000^256500^251500^255600^255500^150"
		+ "^12345678^3140000000000^8120^9540^1420^117.5^5800000^6545678^1^54.2^93.1^090012^2^3500^104455^5^-1000^092310^2^4000"
		+ "^20260914^20^N^15230^28400^812000^1035000^0.21^11800000^104.6^0^^";

	@Test
	@DisplayName("한 건을 읽어 현재가·전일종가·당일 봉·거래량·거래정지를 낸다. 전일종가는 현재가 − 부호 붙인 전일대비다")
	void parsesOne() {
		List<KisTradeMessage.KisTrade> trades = KisTradeMessage.parse("0|H0STCNT0|001|" + BODY_46);

		assertThat(trades).hasSize(1);
		KisTradeMessage.KisTrade t = trades.getFirst();
		assertThat(t.stockCode()).isEqualTo("005930");
		assertThat(t.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 14));
		assertThat(t.tradeTime()).isEqualTo(LocalTime.of(15, 12, 30));
		assertThat(t.currentPrice()).isEqualTo(255_500);
		assertThat(t.previousClose()).isEqualTo(252_000L);
		assertThat(t.sessionOpen()).isEqualTo(252_000L);
		assertThat(t.sessionHigh()).isEqualTo(256_500L);
		assertThat(t.sessionLow()).isEqualTo(251_500L);
		assertThat(t.sessionVolume()).isEqualTo(12_345_678L);
		assertThat(t.suspended()).isFalse();
		assertThat(t.marketCls()).isNull();
	}

	@Test
	@DisplayName("하락(부호 5)이면 전일종가가 현재가보다 크고, 보합(3)이면 같다")
	void signAppliesToPreviousClose() {
		String down = BODY_46.replace("^255500^2^3500^", "^255500^5^3500^");
		assertThat(KisTradeMessage.parse("0|H0STCNT0|001|" + down).getFirst().previousClose()).isEqualTo(259_000L);

		String flat = BODY_46.replace("^255500^2^3500^", "^255500^3^0^");
		assertThat(KisTradeMessage.parse("0|H0STCNT0|001|" + flat).getFirst().previousClose()).isEqualTo(255_500L);
	}

	@Test
	@DisplayName("애프터마켓 도입으로 맨 끝에 필드가 하나 더 붙어도(47) 읽히고 장 구분이 채워진다")
	void acceptsAppendedMarketClsCode() {
		List<KisTradeMessage.KisTrade> trades = KisTradeMessage.parse("0|H0STCNT0|001|" + BODY_46 + "^3");

		assertThat(trades).hasSize(1);
		assertThat(trades.getFirst().marketCls()).isEqualTo("3");
		assertThat(trades.getFirst().currentPrice()).isEqualTo(255_500);
	}

	@Test
	@DisplayName("체결이 몰려 한 줄에 여러 건이 오면(003) 건수만큼 나눈다")
	void splitsMultipleRecords() {
		String second = BODY_46.replace("005930^151230^255500", "000660^151231^1647000");
		String third = BODY_46.replace("005930^151230^255500", "005380^151231^383500");
		List<KisTradeMessage.KisTrade> trades = KisTradeMessage.parse(
			"0|H0STCNT0|003|" + BODY_46 + "^" + second + "^" + third);

		assertThat(trades).extracting(KisTradeMessage.KisTrade::stockCode).containsExactly("005930", "000660", "005380");
		assertThat(trades.get(1).currentPrice()).isEqualTo(1_647_000);
	}

	@Test
	@DisplayName("시가·고가·저가가 0 이면 아직 없는 것이라 null 이고, 거래정지 Y 는 suspended 다")
	void zeroBarIsNullAndHaltIsSuspended() {
		String preOpen = BODY_46.replace("^252000^256500^251500^", "^0^0^0^").replace("^20260914^20^N^", "^20260914^20^Y^");
		KisTradeMessage.KisTrade t = KisTradeMessage.parse("0|H0STCNT0|001|" + preOpen).getFirst();

		assertThat(t.sessionOpen()).isNull();
		assertThat(t.sessionHigh()).isNull();
		assertThat(t.sessionLow()).isNull();
		assertThat(t.suspended()).isTrue();
	}

	@Test
	@DisplayName("필드가 모자라거나 건수로 나누어떨어지지 않거나 다른 메시지면 빈 목록이다 — 예외를 던지지 않는다")
	void rejectsMalformed() {
		assertThat(KisTradeMessage.parse("0|H0STCNT0|001|005930^151230^255500")).isEmpty();
		assertThat(KisTradeMessage.parse("0|H0STCNT0|002|" + BODY_46 + "^extra")).isEmpty();
		assertThat(KisTradeMessage.parse("0|H0STASP0|001|" + BODY_46)).isEmpty();
		assertThat(KisTradeMessage.parse("0|H0STCNT0|abc|" + BODY_46)).isEmpty();
		assertThat(KisTradeMessage.parse("{\"header\":{\"tr_id\":\"PINGPONG\"}}")).isEmpty();
		assertThat(KisTradeMessage.parse("")).isEmpty();
		assertThat(KisTradeMessage.parse(null)).isEmpty();
	}

	@Test
	@DisplayName("JSON 은 체결 줄이 아니다")
	void detectsTradeLine() {
		assertThat(KisTradeMessage.isTradeLine("0|H0STCNT0|001|x")).isTrue();
		assertThat(KisTradeMessage.isTradeLine("{\"header\":{}}")).isFalse();
		assertThat(KisTradeMessage.isTradeLine("")).isFalse();
	}
}
