package com.finch.domain.ledger.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.TransactionRow;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 수익률 계산식과 반올림 (erd.md §2.5, backConvention 6장). 계산 위치가 {@code TransactionRes.from} 하나라 여기서만 본다.
 * Docker 없이 돈다 — 프로젝션 인터페이스를 직접 구현해 행을 만든다.
 */
class TransactionResTest {

	@Test
	@DisplayName("SELL 수익률 = realized_profit / (avg_buy_price × quantity) × 100, 둘째 자리 HALF_UP")
	void computesRateFromSnapshotCost() {
		// 평단 71,000 × 5주 = 355,000 원가에 12,500 이익 → 3.5211… → 3.52
		TransactionRes res = TransactionRes.from(row(LedgerType.SELL, 73_500L, 5L, 71_000L, 12_500L, 367_500L, null));

		assertThat(res.realizedProfitRate()).isEqualByComparingTo(new BigDecimal("3.52"));
		assertThat(res.realizedProfitRate().scale()).isEqualTo(2);
	}

	@Test
	@DisplayName("반올림은 HALF_UP 이다 — 0.125 는 0.13, 손실은 음수 그대로")
	void roundsHalfUp() {
		// 원가 800 에 이익 1 → 0.125 → 0.13
		assertThat(TransactionRes.from(row(LedgerType.SELL, 801L, 1L, 800L, 1L, 801L, null)).realizedProfitRate())
			.isEqualByComparingTo(new BigDecimal("0.13"));
		// 원가 100,000 에 손실 -1,234 → -1.234 → -1.23
		assertThat(TransactionRes.from(row(LedgerType.SELL, 98_766L, 1L, 100_000L, -1_234L, 98_766L, null))
			.realizedProfitRate()).isEqualByComparingTo(new BigDecimal("-1.23"));
	}

	@Test
	@DisplayName("BUY·DEPOSIT·WITHDRAWAL 은 수익률이 null 이다 — 분모가 될 스냅샷이 없다")
	void nonSellHasNoRate() {
		assertThat(TransactionRes.from(row(LedgerType.BUY, 73_500L, 5L, null, null, 367_500L, null)).realizedProfitRate())
			.isNull();
		assertThat(TransactionRes.from(row(LedgerType.DEPOSIT, null, null, null, null, 1_000_000L, "KAKAOPAY"))
			.realizedProfitRate()).isNull();
		assertThat(TransactionRes.from(row(LedgerType.WITHDRAWAL, null, null, null, null, 500_000L, null))
			.realizedProfitRate()).isNull();
	}

	@Test
	@DisplayName("원가가 0 이면 나누기 예외 대신 null 이다")
	void zeroCostYieldsNull() {
		assertThat(TransactionRes.from(row(LedgerType.SELL, 100L, 1L, 0L, 100L, 100L, null)).realizedProfitRate())
			.isNull();
	}

	@Test
	@DisplayName("occurredAt 은 KST 오프셋 표기이고 유형은 문자열에서 enum 으로 읽는다")
	void mapsTimeAndType() {
		TransactionRes res = TransactionRes.from(row(LedgerType.DEPOSIT, null, null, null, null, 1_000_000L, "TRANSFER"));

		assertThat(res.type()).isEqualTo(LedgerType.DEPOSIT);
		assertThat(res.occurredAt().toString()).isEqualTo("2026-08-20T14:31:02+09:00");
		assertThat(res.paymentMethod()).isEqualTo("TRANSFER");
		assertThat(res.amount()).isEqualTo(1_000_000L);
	}

	private static TransactionRow row(LedgerType type, Long price, Long quantity, Long avgBuyPrice,
		Long realizedProfit, long amount, String paymentMethod) {
		return new TransactionRow() {
			@Override
			public Long getId() {
				return 1L;
			}

			@Override
			public String getType() {
				return type.name();
			}

			@Override
			public Instant getOccurredAt() {
				return Instant.parse("2026-08-20T05:31:02Z");
			}

			@Override
			public String getStockCode() {
				return price == null ? null : "005930";
			}

			@Override
			public String getStockName() {
				return price == null ? null : "삼성전자";
			}

			@Override
			public Long getPrice() {
				return price;
			}

			@Override
			public Long getQuantity() {
				return quantity;
			}

			@Override
			public Long getAvgBuyPrice() {
				return avgBuyPrice;
			}

			@Override
			public Long getRealizedProfit() {
				return realizedProfit;
			}

			@Override
			public long getAmount() {
				return amount;
			}

			@Override
			public String getPaymentMethod() {
				return paymentMethod;
			}
		};
	}
}
