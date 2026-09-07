package com.finch.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * `trade` 테이블 (erd.md §2.5). <b>원장 상세</b>다 — {@code BUY}·{@code SELL} 원장 1행과 정확히 1:1 이다 (불변식 6).
 * <p>
 * MVP 는 시장가 즉시 체결이라 접수와 체결이 분리되지 않는다. 주문 1건 = 이 행 1개이고 {@code id} 가 {@code orderId}(apiSpec 7.1)
 * 이자 {@code tradeId}(9.2) 다. <b>별도 order 테이블이 없다.</b> "주문" 이라는 이름의 엔티티를 찾는다면 이것이다.
 * <p>
 * <b>여기 행이 있으면 이미 체결됐다.</b> 상태 컬럼이 없다 — 미체결·취소가 없기 때문이다 (contracts C43). 그래서 체결 트랜잭션에서
 * 원장 기록 직후에만 만들어지고 원장과 마찬가지로 바뀌지 않는다 ({@link Immutable}). {@code Withdrawal} 과 같은 규칙이다.
 * <p>
 * <b>매도만 {@code avgBuyPrice}·{@code realizedProfit} 을 갖는다</b> ({@code ck_trade_side_columns}). 평단은 매도 후에도
 * 바뀌므로(재매수) 그때의 값을 여기 박아 두지 않으면 과거 수익률을 재현할 수 없다. {@code realizedProfitRate}(apiSpec 8.2)는
 * 저장하지 않고 이 둘로 계산한다. 정적 팩토리를 {@link #buy}·{@link #sell} 로 나눈 이유가 이것이다 — 매수에 평단을 넣거나
 * 매도에 빠뜨리는 조합을 컴파일 시점에 없앤다.
 */
@Entity
@Table(name = "trade")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trade {

	/** apiSpec 7.1 의 {@code orderId}, 9.2 의 {@code tradeId}. */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 짝이 되는 원장 행 ({@code uq_trade_ledger}). 응답의 {@code cashBalanceAfter} 는 그 행이 들고 있다. */
	@Column(nullable = false, updatable = false)
	private Long ledgerEntryId;

	/** 조회·감사용. {@code ix_trade_account_id_desc} 가 내부 API(S11)의 커서 조회를 받친다. */
	@Column(nullable = false, updatable = false)
	private Long accountId;

	/** {@code CHAR(6)} 매핑 이유는 {@code Stock.stockCode} 주석. */
	@JdbcTypeCode(Types.CHAR)
	@Column(name = "stock_code", length = 6, nullable = false, updatable = false)
	private String stockCode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 4, updatable = false)
	private OrderSide side;

	/** {@code ck_trade_quantity} 가 0 이하를 DB 바닥에서 막는다. */
	@Column(nullable = false, updatable = false)
	private long quantity;

	/** 체결 시점 최신 수신 가격. 사용자가 확인 화면에서 본 값이 아니다 (featureSpec 7.3). */
	@Column(nullable = false, updatable = false)
	private long executedPrice;

	/** {@code quantity × executedPrice}. {@code ck_trade_executed_amount} 가 대조한다. 원장 {@code cash_delta} 의 절대값이다. */
	@Column(nullable = false, updatable = false)
	private long executedAmount;

	/** 매도 시 체결 직전 평균 매수가 스냅샷. 매수는 null. */
	@Column(updatable = false)
	private Long avgBuyPrice;

	/** 매도 시 실현손익 {@code (executedPrice − avgBuyPrice) × quantity}. 손실이면 음수. 매수는 null. */
	@Column(updatable = false)
	private Long realizedProfit;

	/** 원장의 {@code occurred_at} 과 같은 값을 넣는다 — 한 사건의 시각은 하나다. */
	@Column(nullable = false, updatable = false)
	private Instant executedAt;

	/** 매수 체결. 평단·실현손익은 없다. 체결 트랜잭션에서 원장 기록 직후에만 부른다. */
	public static Trade buy(Long ledgerEntryId, Long accountId, String stockCode, long quantity, long executedPrice,
		Instant executedAt) {
		return base(ledgerEntryId, accountId, stockCode, OrderSide.BUY, quantity, executedPrice, executedAt);
	}

	/**
	 * 매도 체결. 평단·실현손익이 필수다 — 값은 {@code HoldingCommandService.applySell} 이 돌려준 것을 그대로 넣는다.
	 * 여기서 다시 계산하지 않는다. 계산이 두 곳이면 갈라졌을 때 어느 쪽이 맞는지 알 수 없다.
	 */
	public static Trade sell(Long ledgerEntryId, Long accountId, String stockCode, long quantity, long executedPrice,
		long avgBuyPriceAtSell, long realizedProfit, Instant executedAt) {
		Trade trade = base(ledgerEntryId, accountId, stockCode, OrderSide.SELL, quantity, executedPrice, executedAt);
		trade.avgBuyPrice = avgBuyPriceAtSell;
		trade.realizedProfit = realizedProfit;
		return trade;
	}

	private static Trade base(Long ledgerEntryId, Long accountId, String stockCode, OrderSide side, long quantity,
		long executedPrice, Instant executedAt) {
		Trade trade = new Trade();
		trade.ledgerEntryId = ledgerEntryId;
		trade.accountId = accountId;
		trade.stockCode = stockCode;
		trade.side = side;
		trade.quantity = quantity;
		trade.executedPrice = executedPrice;
		trade.executedAmount = quantity * executedPrice;
		trade.executedAt = executedAt;
		return trade;
	}
}
