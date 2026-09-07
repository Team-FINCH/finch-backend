package com.finch.domain.order.dto.response;

import com.finch.domain.order.exception.OrderErrorCode;

/**
 * `GET /orders/available` 응답 (apiSpec 7.3). 주문 화면의 비율 버튼(10%/25%/50%/최대)과 버튼 활성화가 이 값으로 정해진다 —
 * <b>분모는 서버가 준다</b>, 화면이 계산하지 않는다 (contracts C45).
 * <p>
 * <b>거절도 200 이다.</b> {@code tradable=false} 면 {@code reason} 에 {@link OrderErrorCode} 의 코드 문자열이 담긴다. 에러 코드와
 * 같은 문자열을 쓰는 이유 — 화면이 "주문 버튼을 왜 못 누르나" 와 "주문이 왜 거절됐나" 를 하나의 분기 표로 다루게 하려는 것이다.
 * 사용할 수 있는 값은 셋뿐이다: {@code ORDER_MARKET_CLOSED}·{@code ORDER_STOCK_SUSPENDED}·{@code ORDER_PRICE_UNAVAILABLE}.
 * 예수금·보유 부족은 여기서 사유가 아니다 — 그건 {@code maxQuantity = 0} 으로 드러나고, 주문을 내면 그때 409 다.
 *
 * @param currentPrice    시세 없음이면 null (그때 reason 은 {@code ORDER_PRICE_UNAVAILABLE}).
 * @param availableCash   예수금. 잠그지 않고 읽은 "지금 값" 이다 — 진실은 주문 트랜잭션의 잠근 값이다.
 * @param maxQuantity     매수 {@code floor(cash / price)}, 매도 보유 수량. 시세 없음이면 0.
 * @param holdingQuantity 보유 수량. 없으면 0.
 */
public record OrderAvailableRes(
	boolean tradable,
	String reason,
	Long currentPrice,
	long availableCash,
	long maxQuantity,
	long holdingQuantity
) {

	public static OrderAvailableRes tradable(long currentPrice, long availableCash, long maxQuantity,
		long holdingQuantity) {
		return new OrderAvailableRes(true, null, currentPrice, availableCash, maxQuantity, holdingQuantity);
	}

	/**
	 * 거절. 시세가 있으면(장 마감·거래정지) 그대로 싣고 {@code maxQuantity} 도 계산해 준다 — 화면이 "지금은 안 되지만 얼마까지"
	 * 를 보여줄 수 있다. 시세 없음이면 둘 다 없다.
	 */
	public static OrderAvailableRes rejected(OrderErrorCode reason, Long currentPrice, long availableCash,
		long maxQuantity, long holdingQuantity) {
		return new OrderAvailableRes(false, reason.getCode(), currentPrice, availableCash, maxQuantity, holdingQuantity);
	}
}
