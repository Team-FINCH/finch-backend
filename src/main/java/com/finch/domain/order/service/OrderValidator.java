package com.finch.domain.order.service;

import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.exception.OrderErrorCode;
import com.finch.domain.stock.dto.response.TradabilityRes;
import com.finch.domain.stock.exception.StockErrorCode;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.global.exception.CustomException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 주문 판정 1~4단계 (apiSpec 7.2·11.2). <b>순수 판정만 한다</b> — DB 도 Redis 도 보지 않고, 값을 받아 코드를 정한다.
 * 그래서 스프링 없이 테스트할 수 있고, 판정 <b>순서</b>가 이 클래스 하나에 적혀 있다.
 * <p>
 * 순서는 apiSpec <b>§11.2</b> 를 따른다: 수량 → 종목 존재 → 거래정지 → 장 시간 → 시세 → (락 뒤) 예수금·보유.
 * §7.2 는 거래시간을 1번에 두지만 §11.2 는 종목 존재를 먼저 본다 — 둘이 어긋날 때 §11.2 가 이긴다. 없는 종목에
 * "장 마감" 을 말하면 사용자가 장이 열리길 기다리다 같은 404 를 다시 만난다.
 * <p>
 * 메서드가 셋으로 나뉜 이유 — {@code POST /orders} 는 첫 거절에서 <b>던지고</b>, {@code GET /orders/available} 은 같은
 * 판정을 <b>200 의 {@code reason}</b> 으로 돌려준다 (apiSpec 7.3). 던지는 것과 돌려주는 것을 한 메서드로 하면 어느 한쪽이
 * 예외를 잡아 다시 값으로 바꿔야 하고, 그 자리에서 순서가 갈라진다. 그래서 2~4단계는 {@link Rejection} 을 <b>값으로</b>
 * 돌려주고, 호출자가 던질지 담을지 정한다.
 */
@Component
public class OrderValidator {

	/**
	 * 1단계. 0 이하는 {@code ORDER_QUANTITY_INVALID} 다 (apiSpec 11.2). Bean Validation 의 {@code @Positive} 로 막지 않는 이유 —
	 * 그러면 {@code INVALID_REQUEST} 가 나가고 프론트는 "수량이 0 이하" 분기를 잃는다. {@code WithdrawalReq.amount} 와 같은 판단이다.
	 */
	public void requirePositiveQuantity(long quantity) {
		if (quantity <= 0) {
			throw new CustomException(OrderErrorCode.ORDER_QUANTITY_INVALID);
		}
	}

	/** 2단계 앞부분. 없는 종목·상장폐지는 404 다. {@code available} 도 같다 — 200 의 reason 으로 표현할 값이 없다 (apiSpec 11.2). */
	public void requireExists(TradabilityRes stock) {
		if (!stock.exists()) {
			throw new CustomException(StockErrorCode.STOCK_NOT_FOUND);
		}
	}

	/**
	 * 2단계 뒷부분 ~ 4단계 앞부분: 거래정지 → 장 시간 → 시세. 첫 거절만 돌려준다 — 같은 요청에 사유가 겹치면 판정 순서에서
	 * 먼저 걸리는 코드 하나만 나간다 (apiSpec 11.2).
	 * <p>
	 * <b>시세는 값이 없어도, 있어도 {@code stale} 이면 거절이다.</b> 허용 시간({@code finch.price.stale-after})을 넘긴 마지막 값으로
	 * 체결하면 실제 시장가와 동떨어진 가격에 사는 것이라 featureSpec 7.4 가 막았다. 화면은 그 값을 "시세 지연" 으로 보여줄 수 있지만
	 * 체결가로는 쓰지 않는다.
	 *
	 * @param stock {@link #requireExists} 를 통과한 값이어야 한다. 존재하지 않는 종목은 여기 오지 않는다.
	 */
	public Optional<Rejection> rejectionBeforeExecution(TradabilityRes stock, boolean marketOpen, PriceSnapshot price) {
		if (stock.suspended()) {
			return Optional.of(new Rejection(OrderErrorCode.ORDER_STOCK_SUSPENDED, suspendedDetail(stock.suspendedReason())));
		}
		if (!marketOpen) {
			return Optional.of(new Rejection(OrderErrorCode.ORDER_MARKET_CLOSED, null));
		}
		if (price.currentPrice() == null || price.stale()) {
			return Optional.of(new Rejection(OrderErrorCode.ORDER_PRICE_UNAVAILABLE, null));
		}
		return Optional.empty();
	}

	/**
	 * 4단계 재검증. <b>계좌 락을 잡은 뒤, 잠근 값으로</b> 부른다 — 그 전에 읽은 잔고는 판정 근거가 될 수 없다
	 * ({@code AccountService.lockByUserId} 주석). 가격은 락 <b>밖</b>에서 읽은 값 그대로다 ({@code OrderService} 주석).
	 * <p>
	 * 부족하면 수량을 줄여 체결하지 않고 거부한다 (featureSpec 7.3). 매수 부족은 <b>전부</b> {@code ORDER_INSUFFICIENT_CASH} 다 —
	 * "가격이 변동돼 부족해진 것" 을 가르던 {@code ORDER_PRICE_CHANGED} 는 v0.8 에서 폐기됐다 (contracts C88).
	 * 잔고와 <b>정확히 같은</b> 금액은 통과한다 (전액 매수·전량 매도).
	 *
	 * @param holdingQuantity 보유 수량. 없으면 0. 매수 판정에는 쓰이지 않는다.
	 */
	public void requireAffordable(OrderSide side, long quantity, long price, long cashBalance, long holdingQuantity) {
		if (side.isBuy()) {
			long required = quantity * price;
			if (cashBalance < required) {
				throw new CustomException(OrderErrorCode.ORDER_INSUFFICIENT_CASH,
					Map.of("required", required, "available", cashBalance));
			}
			return;
		}
		if (holdingQuantity < quantity) {
			throw new CustomException(OrderErrorCode.ORDER_INSUFFICIENT_QUANTITY,
				Map.of("required", quantity, "available", holdingQuantity));
		}
	}

	/**
	 * {@code detail.reason} 은 사유가 없어도 키를 남긴다 — 프론트가 "필드가 있으면 보여준다" 가 아니라 "이 코드면 reason 을 읽는다"
	 * 로 분기하게 하려는 것이다 (apiSpec 7.2 "거래정지 사유 포함"). {@code Map.of} 는 null 값을 받지 않아 직접 만든다.
	 */
	private static Map<String, String> suspendedDetail(String reason) {
		Map<String, String> detail = new LinkedHashMap<>();
		detail.put("reason", reason);
		return detail;
	}

	/**
	 * 2~4단계의 거절. {@code POST /orders} 는 {@link #toException()} 으로 던지고, {@code GET /orders/available} 은
	 * {@code code.getCode()} 를 {@code reason} 에 담는다.
	 */
	public record Rejection(OrderErrorCode code, Object detail) {

		public CustomException toException() {
			return new CustomException(code, detail);
		}
	}
}
