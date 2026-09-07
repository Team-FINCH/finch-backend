package com.finch.domain.order.service;

import com.finch.domain.account.dto.response.AccountBalanceRes;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.ledger.dto.response.LedgerEntryRes;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.service.LedgerService;
import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.entity.Trade;
import com.finch.domain.order.repository.TradeRepository;
import com.finch.domain.portfolio.service.HoldingCommandService;
import com.finch.domain.portfolio.service.HoldingCommandService.SellResult;
import com.finch.domain.stock.port.HoldingQueryPort;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 체결 트랜잭션 (apiSpec 7.2 의 4·5단계, erd.md §3.2). <b>이 클래스의 메서드 하나가 트랜잭션 하나</b>다.
 * <p>
 * <b>{@code OrderService} 안의 private 메서드가 아니라 별도 빈인 이유</b> — {@code @Transactional} 은 스프링 프록시가 붙여 주는
 * 것이라 <b>같은 객체 안에서 부르면 프록시를 거치지 않는다</b>(self-invocation). {@code OrderService.place} 가 자기 안의
 * {@code @Transactional execute} 를 부르면 어노테이션은 장식일 뿐 트랜잭션이 열리지 않고, {@code lockByUserId} 가
 * "트랜잭션 밖" 이라고 예외를 던져 첫 주문에서 바로 드러난다. 드러나서 다행이지만 그 방어가 없었다면 락 없이 체결되는 코드가
 * 테스트를 통과했을 것이다. 빈을 나누면 호출이 반드시 프록시를 지난다.
 * <p>
 * <b>시세를 받기만 하고 읽지 않는다.</b> 가격은 호출자가 트랜잭션 <b>밖</b>에서 1회 읽어 넘긴다 — 락을 잡은 채 Redis 를 기다리지
 * 않기 위해서다. 그 대가로 "락을 잡는 사이 가격이 바뀌었을" 수 있는데, 시장가 주문에서 그것은 정상이고(featureSpec 7.3)
 * 재검증(4단계)은 그 가격으로 잠근 잔고를 다시 보는 것이지 가격을 다시 읽는 것이 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutor {

	private final OrderValidator validator;
	private final AccountService accountService;
	private final LedgerService ledgerService;
	private final TradeRepository tradeRepository;
	private final HoldingCommandService holdingCommandService;
	private final HoldingQueryPort holdingQueryPort;

	/**
	 * 잠그고 → 재검증 → 원장 → 체결 → 보유 → 계좌. 순서는 erd.md §3.2 그대로이고 <b>하나라도 실패하면 전부 롤백</b>이다.
	 * <ol>
	 *   <li>{@code account} FOR UPDATE ({@link AccountService#lockByUserId}) — 같은 사용자의 주문·충전·출금이 여기서 줄을 선다.
	 *       <b>락은 이 한 행뿐이다.</b> 보유 행을 또 잠그지 않는다 ({@code HoldingCommandService} 주석 — 두 락은 교착의 씨앗이다).</li>
	 *   <li>잠근 잔고·보유로 재검증 ({@link OrderValidator#requireAffordable}). 부족하면 수량을 줄이지 않고 거부한다.</li>
	 *   <li>원장 INSERT — 매수 {@code −amount}, 매도 {@code +amount}. {@code cash_balance_after} 는 여기서 계산한 값이고 이후 모두가
	 *       이 값을 복사한다.</li>
	 *   <li>{@code trade} INSERT. 매도는 보유 갱신이 돌려준 평단·실현손익을 그대로 넣는다.</li>
	 *   <li>보유 UPSERT ({@link HoldingCommandService}).</li>
	 *   <li>계좌 스냅샷 UPDATE ({@link AccountService#applyTrade}).</li>
	 * </ol>
	 * 보유 수량은 {@link HoldingQueryPort} 로 읽는다 — {@code quantity = 0} 인 행은 empty 라 0 으로 본다. 잠근 뒤라 같은 계좌의
	 * 다른 주문이 이 값을 바꾸고 있을 수 없다.
	 *
	 * @param price 호출자가 트랜잭션 밖에서 읽은 체결가. 여기서 다시 읽지 않는다.
	 */
	@Transactional
	public Execution execute(Long userId, String stockCode, OrderSide side, long quantity, long price) {
		AccountBalanceRes locked = accountService.lockByUserId(userId);
		long holdingQuantity = holdingQueryPort.holdingOf(userId, stockCode)
			.map(HoldingQueryPort.HoldingSnapshot::quantity).orElse(0L);
		validator.requireAffordable(side, quantity, price, locked.cashBalance(), holdingQuantity);

		Instant now = now();
		Execution execution = side.isBuy()
			? buy(locked, stockCode, quantity, price, now)
			: sell(locked, stockCode, quantity, price, now);

		log.info("체결 orderId={} side={} stockCode={} quantity={} price={} cashBalanceAfter={}",
			execution.trade().getId(), side, stockCode, quantity, price, execution.cashBalanceAfter());
		return execution;
	}

	private Execution buy(AccountBalanceRes locked, String stockCode, long quantity, long price, Instant now) {
		long amount = quantity * price;
		long cashBalanceAfter = locked.cashBalance() - amount;
		LedgerEntryRes entry = ledgerService.record(locked.accountId(), LedgerType.BUY, -amount, cashBalanceAfter, now);
		Trade trade = tradeRepository.save(Trade.buy(entry.id(), locked.accountId(), stockCode, quantity, price, now));
		holdingCommandService.applyBuy(locked.accountId(), stockCode, quantity, price);
		accountService.applyTrade(locked.accountId(), cashBalanceAfter);
		return new Execution(trade, cashBalanceAfter);
	}

	/**
	 * 매도. 매수와 순서가 하나 다르다 — <b>보유 갱신이 {@code trade} INSERT 보다 먼저</b>다. {@code trade.avg_buy_price}·
	 * {@code realized_profit} 에 넣을 값을 {@link HoldingCommandService#applySell} 이 돌려주기 때문이다. 실현손익은 거기서
	 * 한 번만 계산하고 여기서는 옮겨 적는다. 전량 매도면 보유 행은 {@code quantity = 0} 으로 남고(erd.md §2.6) {@code trade} 는
	 * 그때의 평단을 그대로 갖는다.
	 */
	private Execution sell(AccountBalanceRes locked, String stockCode, long quantity, long price, Instant now) {
		long amount = quantity * price;
		long cashBalanceAfter = locked.cashBalance() + amount;
		LedgerEntryRes entry = ledgerService.record(locked.accountId(), LedgerType.SELL, amount, cashBalanceAfter, now);
		SellResult sold = holdingCommandService.applySell(locked.accountId(), stockCode, quantity, price);
		Trade trade = tradeRepository.save(Trade.sell(entry.id(), locked.accountId(), stockCode, quantity, price,
			sold.avgBuyPriceAtSell(), sold.realizedProfit(), now));
		accountService.applyTrade(locked.accountId(), cashBalanceAfter);
		return new Execution(trade, cashBalanceAfter);
	}

	/**
	 * 체결 결과. {@code cashBalanceAfter} 를 {@code Trade} 와 함께 돌려주는 이유 — 응답의 잔고는 <b>이 트랜잭션이 기록한 값</b>이어야
	 * 한다. 커밋 뒤에 계좌를 다시 읽으면 그 사이 끼어든 충전·출금이 섞인 값이 나와 원장 행의 {@code cash_balance_after} 와 어긋난다.
	 */
	public record Execution(Trade trade, long cashBalanceAfter) {
	}

	/**
	 * 이 트랜잭션의 "지금". 원장·체결이 같은 값을 쓴다 — 한 사건의 시각은 하나다. 마이크로초로 자르는 이유는
	 * {@code WithdrawalService.now} 와 같다 — 응답의 {@code executedAt} 이 DB 에서 다시 읽은 값과 같아야 한다.
	 */
	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
