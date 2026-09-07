package com.finch.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.exception.OrderErrorCode;
import com.finch.domain.order.service.OrderValidator.Rejection;
import com.finch.domain.stock.dto.response.TradabilityRes;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.global.apiPayload.code.BaseErrorCode;
import com.finch.global.exception.CustomException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 판정 1~4단계의 <b>순서</b>를 고정한다 (apiSpec 11.2). 스프링 없이 돈다 — 판정기가 DB 도 Redis 도 보지 않게 만든 이유가 이것이다.
 * <p>
 * 각 단계는 앞 단계를 통과했을 때만 나와야 한다. 그래서 "두 사유가 겹친" 입력을 넣고 먼저 걸리는 코드 하나만 나오는지 본다.
 */
class OrderValidatorTest {

	private static final TradabilityRes ACTIVE = new TradabilityRes(true, "삼성전자", false, null);
	private static final TradabilityRes SUSPENDED = new TradabilityRes(true, "정지종목", true, "관리종목");
	private static final PriceSnapshot FRESH = new PriceSnapshot(70_000L, 0L, BigDecimal.ZERO, Instant.now(), false);
	private static final PriceSnapshot STALE = new PriceSnapshot(70_000L, 0L, BigDecimal.ZERO, Instant.now(), true);

	private final OrderValidator validator = new OrderValidator();

	@Nested
	@DisplayName("1단계 — 수량")
	class Quantity {

		@Test
		@DisplayName("0·음수는 ORDER_QUANTITY_INVALID, 1 은 통과")
		void rejectsNonPositive() {
			assertCode(() -> validator.requirePositiveQuantity(0), OrderErrorCode.ORDER_QUANTITY_INVALID);
			assertCode(() -> validator.requirePositiveQuantity(-1), OrderErrorCode.ORDER_QUANTITY_INVALID);
			assertThatCode(() -> validator.requirePositiveQuantity(1)).doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("2단계 — 종목")
	class Stock {

		@Test
		@DisplayName("없는 종목·상장폐지는 STOCK_NOT_FOUND")
		void rejectsMissing() {
			assertCode(() -> validator.requireExists(TradabilityRes.missing()), StockErrorCode.STOCK_NOT_FOUND);
			assertThatCode(() -> validator.requireExists(ACTIVE)).doesNotThrowAnyException();
		}

		/** 거래정지가 장 마감·시세 없음보다 앞이다 — 정지 종목에 "장 마감" 을 말하면 장이 열려도 같은 벽을 다시 만난다. */
		@Test
		@DisplayName("거래정지는 장 마감·시세 없음이 겹쳐도 ORDER_STOCK_SUSPENDED 이고 detail.reason 을 싣는다")
		void suspendedComesFirst() {
			Optional<Rejection> rejection = validator.rejectionBeforeExecution(SUSPENDED, false, PriceSnapshot.missing());

			assertThat(rejection).isPresent();
			assertThat(rejection.get().code()).isEqualTo(OrderErrorCode.ORDER_STOCK_SUSPENDED);
			assertThat(rejection.get().detail()).isEqualTo(Map.of("reason", "관리종목"));
		}

		@Test
		@DisplayName("사유가 없는 거래정지도 detail.reason 키는 남긴다 (값은 null)")
		void suspendedWithoutReasonKeepsKey() {
			TradabilityRes noReason = new TradabilityRes(true, "정지종목", true, null);

			Rejection rejection = validator.rejectionBeforeExecution(noReason, true, FRESH).orElseThrow();

			assertThat(rejection.detail()).isEqualTo(Collections.singletonMap("reason", null));
		}
	}

	@Nested
	@DisplayName("3단계 — 장 시간")
	class Market {

		@Test
		@DisplayName("장 마감은 시세 없음이 겹쳐도 ORDER_MARKET_CLOSED")
		void closedComesBeforePrice() {
			Rejection rejection = validator.rejectionBeforeExecution(ACTIVE, false, PriceSnapshot.missing()).orElseThrow();

			assertThat(rejection.code()).isEqualTo(OrderErrorCode.ORDER_MARKET_CLOSED);
			assertThat(rejection.detail()).isNull();
		}
	}

	@Nested
	@DisplayName("4단계 앞 — 시세")
	class Price {

		@Test
		@DisplayName("값 없음은 ORDER_PRICE_UNAVAILABLE")
		void missingPrice() {
			Rejection rejection = validator.rejectionBeforeExecution(ACTIVE, true, PriceSnapshot.missing()).orElseThrow();

			assertThat(rejection.code()).isEqualTo(OrderErrorCode.ORDER_PRICE_UNAVAILABLE);
		}

		/** 마지막 값이 있어도 허용 시간을 넘겼으면 체결가로 쓰지 않는다 (featureSpec 7.4). 화면 표시와 체결의 기준이 다른 지점이다. */
		@Test
		@DisplayName("값이 있어도 stale 이면 ORDER_PRICE_UNAVAILABLE")
		void stalePrice() {
			Rejection rejection = validator.rejectionBeforeExecution(ACTIVE, true, STALE).orElseThrow();

			assertThat(rejection.code()).isEqualTo(OrderErrorCode.ORDER_PRICE_UNAVAILABLE);
		}

		@Test
		@DisplayName("활성·장중·정상 시세면 거절이 없다")
		void passes() {
			assertThat(validator.rejectionBeforeExecution(ACTIVE, true, FRESH)).isEmpty();
		}
	}

	@Nested
	@DisplayName("4단계 — 재검증 (락 뒤)")
	class Affordability {

		@Test
		@DisplayName("매수 — 예수금 < 수량×가격 이면 ORDER_INSUFFICIENT_CASH 이고 detail 은 {required, available}")
		void insufficientCash() {
			assertThatThrownBy(() -> validator.requireAffordable(OrderSide.BUY, 10, 70_000, 699_999, 0))
				.isInstanceOf(CustomException.class)
				.satisfies(e -> {
					CustomException ce = (CustomException) e;
					assertThat(ce.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INSUFFICIENT_CASH);
					assertThat(ce.getDetail()).isEqualTo(Map.of("required", 700_000L, "available", 699_999L));
				});
		}

		@Test
		@DisplayName("매수 — 정확히 같은 금액은 통과한다 (전액 매수). 보유 수량은 보지 않는다")
		void exactCashPasses() {
			assertThatCode(() -> validator.requireAffordable(OrderSide.BUY, 10, 70_000, 700_000, 0))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("매도 — 보유 < 수량 이면 ORDER_INSUFFICIENT_QUANTITY 이고 detail 은 {required, available}. 예수금은 보지 않는다")
		void insufficientQuantity() {
			assertThatThrownBy(() -> validator.requireAffordable(OrderSide.SELL, 10, 70_000, 0, 9))
				.isInstanceOf(CustomException.class)
				.satisfies(e -> {
					CustomException ce = (CustomException) e;
					assertThat(ce.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INSUFFICIENT_QUANTITY);
					assertThat(ce.getDetail()).isEqualTo(Map.of("required", 10L, "available", 9L));
				});
		}

		@Test
		@DisplayName("매도 — 정확히 같은 수량은 통과한다 (전량 매도)")
		void exactQuantityPasses() {
			assertThatCode(() -> validator.requireAffordable(OrderSide.SELL, 10, 70_000, 0, 10))
				.doesNotThrowAnyException();
		}
	}

	private static void assertCode(Runnable action, BaseErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOf(CustomException.class)
			.extracting(e -> ((CustomException) e).getErrorCode())
			.isEqualTo(expected);
	}
}
