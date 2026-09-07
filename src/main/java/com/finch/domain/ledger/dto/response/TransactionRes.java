package com.finch.domain.ledger.dto.response;

import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.TransactionRow;
import com.finch.global.util.KstTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * `GET /transactions` 의 {@code items} 한 행 (apiSpec 8.2). 유형에 따라 채워지는 필드가 다르고, 나머지는 {@code null}
 * 로 <b>내려간다</b> — 키를 빼지 않는다. 프론트가 유형별로 다른 모양을 파싱하지 않게 한 행의 모양은 하나다.
 *
 * <ul>
 *   <li>BUY·SELL — 종목·체결가·수량·체결 금액. SELL 은 실현손익·수익률까지.</li>
 *   <li>DEPOSIT — 금액·결제수단.</li>
 *   <li>WITHDRAWAL — 금액만. 출금은 수단이 없다 (apiSpec 4.5).</li>
 *   <li>INITIAL_GRANT — 금액만. 현재 발행되지 않지만 올 수 있는 값이다 (apiSpec 8.2).</li>
 * </ul>
 *
 * @param amount             양수 절대값. 부호는 원장이 갖고 화면은 {@code type} 으로 방향을 표시한다.
 * @param realizedProfitRate SELL 에서만. {@code realized_profit / (avg_buy_price × quantity) × 100}, 소수 둘째 자리 HALF_UP.
 *                           저장하지 않고 여기서 계산한다 (erd.md §2.5) — <b>계산 위치는 이 팩토리 하나다.</b>
 * @param paymentMethod      DEPOSIT 에서만. 문자열이다 — ledger 는 deposit 의 enum 을 import 하지 않는다.
 */
public record TransactionRes(
	Long transactionId,
	LedgerType type,
	OffsetDateTime occurredAt,
	String stockCode,
	String stockName,
	Long price,
	Long quantity,
	long amount,
	Long realizedProfit,
	BigDecimal realizedProfitRate,
	String paymentMethod
) {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	public static TransactionRes from(TransactionRow row) {
		return new TransactionRes(
			row.getId(),
			LedgerType.valueOf(row.getType()),
			KstTime.toResponse(row.getOccurredAt()),
			row.getStockCode(),
			row.getStockName(),
			row.getPrice(),
			row.getQuantity(),
			row.getAmount(),
			row.getRealizedProfit(),
			profitRate(row.getRealizedProfit(), row.getAvgBuyPrice(), row.getQuantity()),
			row.getPaymentMethod());
	}

	/**
	 * 수익률. 분모는 <b>매도 직전 평단 × 수량</b>(= 그 수량의 매입 원가)이다 — 평단은 매도 뒤 바뀌므로 trade 가 스냅샷으로
	 * 들고 있는 값을 쓴다. 비율은 서버가 계산하고 둘째 자리 HALF_UP 이다 (backConvention 6장).
	 * <p>
	 * BUY 는 셋 다 null 이라 null 이다. 분모가 0 인 SELL 은 DB 제약상 나올 수 없지만({@code avg_buy_price} 는 SELL 에서
	 * NOT NULL, {@code quantity > 0}), 평단 0 인 보유(무상 취득 같은 확장)가 생겨도 나누기 예외 대신 null 로 둔다.
	 */
	private static BigDecimal profitRate(Long realizedProfit, Long avgBuyPrice, Long quantity) {
		if (realizedProfit == null || avgBuyPrice == null || quantity == null) {
			return null;
		}
		long cost = avgBuyPrice * quantity;
		if (cost == 0) {
			return null;
		}
		return BigDecimal.valueOf(realizedProfit)
			.multiply(HUNDRED)
			.divide(BigDecimal.valueOf(cost), 2, RoundingMode.HALF_UP);
	}
}
