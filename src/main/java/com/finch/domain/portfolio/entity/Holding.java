package com.finch.domain.portfolio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * `holding` 테이블 (erd.md §2.6). 계좌당 종목 하나이고 <b>그 행이 현재 잔고 그 자체다</b> — 과거 보유 이력은 `trade` 로만 남는다.
 * <p>
 * 원장에서 파생된 스냅샷이라 (erd.md §1.3) 체결 트랜잭션 안에서만 바뀐다. 진실은 `trade` 이고 이 값은 매번
 * {@code SUM} 하지 않으려고 물질화해 둔 것이다 (불변식 3).
 * <p>
 * <b>전량 매도해도 행을 지우지 않는다.</b> {@code quantity = 0}, {@code avg_buy_price = 0} 으로 남긴다 — 같은 종목을 다시 사면
 * INSERT 경합 없이 이 행을 갱신하면 되기 때문이다. 조회는 {@code quantity > 0} 으로 거르므로 화면에서는 사라진다.
 * {@code ck_holding_zeroed} 가 "수량 0 인데 평단이 남아 있는" 상태를 DB 에서 막는다.
 */
@Entity
@Table(name = "holding")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holding {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private Long accountId;

	/** {@code CHAR(6)} 매핑 이유는 {@code Stock.stockCode} 주석. */
	@JdbcTypeCode(Types.CHAR)
	@Column(name = "stock_code", length = 6, nullable = false, updatable = false)
	private String stockCode;

	@Column(nullable = false)
	private long quantity;

	/** 가중평균 매입 단가. 원 단위 정수라 매수마다 버림이 쌓인다 ({@link #buy} 주석). */
	@Column(nullable = false)
	private long avgBuyPrice;

	@Column(nullable = false)
	private Instant updatedAt;

	/** 처음 사는 종목. 첫 매수가가 곧 평단이다. */
	public static Holding open(Long accountId, String stockCode, long quantity, long price, Instant now) {
		Holding holding = new Holding();
		holding.accountId = accountId;
		holding.stockCode = stockCode;
		holding.quantity = quantity;
		holding.avgBuyPrice = price;
		holding.updatedAt = now;
		return holding;
	}

	/**
	 * 매수 반영. 새 평단 = {@code (평단 × 기존수량 + 체결가 × 매수수량) / (기존수량 + 매수수량)}.
	 * <p>
	 * <b>정수 나눗셈이라 버림이다.</b> 평단이 원 단위 {@code long} 이기 때문이고, 그래서 평단은 실제 매입 원가보다 조금 작을 수 있다.
	 * 소수점을 들고 다니면 화면·주문·실현손익이 각자 반올림하게 되어 더 어긋난다 — 저장은 정수로 고정하고 어긋남을 한 방향으로 몬다.
	 * <p>
	 * 전량 매도로 {@code quantity = 0} 이 된 행을 다시 사면 기존 평단이 0 이므로 <b>새 가격이 그대로 평단</b>이 된다.
	 */
	public void buy(long addQuantity, long price, Instant now) {
		long totalCost = avgBuyPrice * quantity + price * addQuantity;
		this.quantity += addQuantity;
		this.avgBuyPrice = totalCost / this.quantity;
		this.updatedAt = now;
	}

	/**
	 * 매도 반영. 수량만 줄이고 평단은 그대로다 — 판 만큼의 원가는 실현손익으로 빠져나갔고 남은 수량의 평단은 변하지 않는다.
	 * <p>
	 * 전량 매도면 평단도 0 으로 지운다 ({@code ck_holding_zeroed}). 남겨 두면 "보유하지 않는데 평단이 있는" 행이 된다.
	 */
	public void sell(long sellQuantity, Instant now) {
		this.quantity -= sellQuantity;
		if (this.quantity == 0) {
			this.avgBuyPrice = 0;
		}
		this.updatedAt = now;
	}
}
