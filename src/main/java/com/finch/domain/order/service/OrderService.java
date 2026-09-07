package com.finch.domain.order.service;

import com.finch.domain.account.service.AccountService;
import com.finch.domain.order.dto.request.OrderReq;
import com.finch.domain.order.dto.response.OrderAvailableRes;
import com.finch.domain.order.dto.response.OrderRes;
import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.service.OrderValidator.Rejection;
import com.finch.domain.stock.dto.response.TradabilityRes;
import com.finch.domain.stock.port.HoldingQueryPort;
import com.finch.domain.stock.port.PriceQueryPort;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.domain.stock.service.StockService;
import com.finch.global.util.MarketClock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 주문 (apiSpec 7장, featureSpec 7.3·7.4). 판정 1~4단계를 {@link OrderValidator} 에 묻고, 체결은 {@code OrderExecutor} 에 맡긴다.
 * <p>
 * <b>이 클래스에는 {@code @Transactional} 이 없다.</b> 시세는 Redis 에 있고 종목·장 시간 판정은 DB 락이 필요 없는데,
 * 그것들을 트랜잭션 안에서 하면 계좌 행을 잠근 채 Redis 응답을 기다리게 된다. 잠그는 시간은 짧을수록 좋다 — 같은 사용자의
 * 다음 주문·충전·출금이 전부 그 락을 기다린다. 그래서 잠그기 전에 할 수 있는 판정은 여기서 끝내고, 잠가야 하는 것만
 * 트랜잭션 빈으로 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

	private final OrderValidator validator;
	private final OrderExecutor executor;
	private final StockService stockService;
	private final MarketClock marketClock;
	private final PriceQueryPort priceQueryPort;
	private final HoldingQueryPort holdingQueryPort;
	private final AccountService accountService;

	/**
	 * 시장가 주문 (apiSpec 7.1·7.2). 판정 순서는 apiSpec 11.2 그대로다 — (멱등성은 필터가 앞에서) → {@code side} 열거값(파싱) →
	 * 수량 0 이하 → 종목 존재 → 거래정지 → 장 시간 → 시세 → <b>트랜잭션 진입</b> → 잠근 잔고·보유 재검증 → 기록.
	 * <p>
	 * <b>시세는 여기서 1회 읽고 그 값으로 체결한다.</b> 트랜잭션 밖이다. 이유는 {@link OrderExecutor} 주석 — 락을 잡은 채 Redis 를
	 * 기다리지 않는다. 읽은 값이 {@code stale} 이면 체결하지 않는다 ({@link OrderValidator#rejectionBeforeExecution}).
	 * <p>
	 * <b>재전송은 {@code IdempotencyFilter} 가 막는다.</b> {@code finch.idempotency.paths} 에 이 경로가 있어 같은 키는 컨트롤러에
	 * 닿지 않고 최초 응답이 재생된다. 서비스는 재생 경로를 갖지 않는다 — 출금과 같다.
	 */
	public OrderRes place(Long userId, OrderReq request) {
		validator.requirePositiveQuantity(request.quantity());
		TradabilityRes stock = stockService.getTradable(request.stockCode());
		validator.requireExists(stock);

		PriceSnapshot price = priceQueryPort.latest(request.stockCode());
		validator.rejectionBeforeExecution(stock, marketClock.isOpen(), price)
			.ifPresent(rejection -> {
				throw rejection.toException();
			});

		OrderExecutor.Execution execution = executor.execute(userId, request.stockCode(), request.side(),
			request.quantity(), price.currentPrice());
		return OrderRes.of(execution.trade(), stock.stockName(), execution.cashBalanceAfter());
	}

	/**
	 * 주문 가능 정보 (apiSpec 7.3). 판정은 주문과 <b>같은 순서, 같은 판정기</b>를 지난다 — 화면이 "가능" 이라 했는데 주문이 다른
	 * 이유로 거절되는 일을 판정기를 공유해서 막는다. 다른 점은 거절을 던지지 않고 {@code reason} 에 담는 것뿐이다. 없는 종목만
	 * 404 다 — 200 의 {@code reason} 으로 표현할 값이 없다.
	 * <p>
	 * 예수금·보유는 잠그지 않고 읽는다. 화면 표시용이고, 진실은 주문 트랜잭션이 잠근 값이다 ({@code AccountService.getBalance} 주석).
	 * <b>거래정지·장 마감이어도 시세가 있으면 {@code maxQuantity} 를 계산해 준다</b> — 화면이 "지금은 안 되지만" 을 보여줄 수 있다.
	 */
	public OrderAvailableRes available(Long userId, String stockCode, OrderSide side) {
		TradabilityRes stock = stockService.getTradable(stockCode);
		validator.requireExists(stock);

		PriceSnapshot price = priceQueryPort.latest(stockCode);
		long availableCash = accountService.getBalance(userId).cashBalance();
		long holdingQuantity = holdingQueryPort.holdingOf(userId, stockCode)
			.map(HoldingQueryPort.HoldingSnapshot::quantity).orElse(0L);
		Long currentPrice = price.currentPrice();
		long maxQuantity = maxQuantity(side, currentPrice, availableCash, holdingQuantity);

		Optional<Rejection> rejection = validator.rejectionBeforeExecution(stock, marketClock.isOpen(), price);
		if (rejection.isPresent()) {
			return OrderAvailableRes.rejected(rejection.get().code(), currentPrice, availableCash, maxQuantity,
				holdingQuantity);
		}
		return OrderAvailableRes.tradable(currentPrice, availableCash, maxQuantity, holdingQuantity);
	}

	/** 매수 {@code floor(cash / price)}, 매도 보유 수량. 가격이 없으면(값 없음) 0 — 나눌 수가 없다. {@code stale} 인 값으로는 계산한다. */
	private static long maxQuantity(OrderSide side, Long price, long availableCash, long holdingQuantity) {
		if (!side.isBuy()) {
			return holdingQuantity;
		}
		if (price == null || price <= 0) {
			return 0;
		}
		return availableCash / price;
	}
}
