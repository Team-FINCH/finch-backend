package com.finch.domain.portfolio.service;

import com.finch.domain.portfolio.entity.Holding;
import com.finch.domain.portfolio.repository.HoldingRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 체결이 보유를 바꾸는 유일한 통로 (erd.md §3.5, S9 주문이 부른다). 읽기와 나누어 둔 이유는 규모가 아니라 <b>트랜잭션 규칙이
 * 반대</b>이기 때문이다 — 조회는 자기 트랜잭션을 열고, 여기는 열지 않는다.
 * <p>
 * <b>{@code MANDATORY} 다.</b> 보유 갱신은 원장·체결과 한 트랜잭션에서 커밋되거나 함께 롤백되어야 불변식 3
 * (보유 수량 = 체결 합)이 성립한다. 자기 트랜잭션을 열면 주문이 뒤에서 실패해도 보유만 늘어난 채로 남는다 —
 * <b>없는 주식을 가진 계좌</b>가 만들어지고 원장으로 되돌릴 수도 없다. {@code AccountService.applyDeposit} 과 같은 이유다.
 * <p>
 * <b>락을 걸지 않는다.</b> 호출자가 {@code AccountService.lockByUserId} 로 계좌 행을 이미 잠갔고, 한 계좌의 주문은 그
 * 지점에서 직렬화된다. 여기서 보유 행을 또 잠그면 두 락의 획득 순서가 생겨 교착의 씨앗이 된다 — 락은 한 곳이어야 한다.
 */
@Service
@RequiredArgsConstructor
public class HoldingCommandService {

	private final HoldingRepository holdingRepository;

	/**
	 * 매수 체결을 반영한다. 처음 사는 종목이면 행을 만들고, 아니면 가중평균을 다시 낸다 ({@link Holding#buy}).
	 * <p>
	 * <b>{@code quantity = 0} 인 행이 있으면 그 행을 다시 쓴다.</b> 전량 매도 후 재매수가 이 경로다 — INSERT 하지 않으므로
	 * {@code uq_holding_account_stock} 과 경합하지 않는다. 그 행을 남겨 둔 이유가 이것이다.
	 *
	 * @return 반영 후 평균 매수가. 호출자가 {@code trade.avg_buy_price} 에 기록한다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public long applyBuy(Long accountId, String stockCode, long quantity, long price) {
		Instant now = Instant.now();
		Holding holding = holdingRepository.findByAccountIdAndStockCode(accountId, stockCode).orElse(null);
		if (holding == null) {
			return holdingRepository.save(Holding.open(accountId, stockCode, quantity, price, now)).getAvgBuyPrice();
		}
		holding.buy(quantity, price, now);
		return holding.getAvgBuyPrice();
	}

	/**
	 * 매도 체결을 반영하고 실현손익을 낸다.
	 * <p>
	 * <b>실현손익 = (체결가 − 평단) × 매도 수량</b> 이고, 평단은 <b>줄이기 전</b> 값이다. 매도는 평단을 바꾸지 않는다 —
	 * 판 만큼의 원가가 손익으로 빠져나갈 뿐 남은 수량의 원가는 그대로다.
	 * <p>
	 * 수량이 모자라면 {@code IllegalStateException} 이다. 사용자에게 보일 에러가 아니라 <b>버그</b>이기 때문이다 — 주문(S9)이
	 * 보유를 먼저 확인하고 계좌 락 안에서 부르므로 여기까지 오면 확인을 건너뛴 것이다. {@code ck_holding_quantity} 가
	 * 마지막 방어선이지만 그때는 제약 위반 메시지만 남아 어디서 잘못됐는지 알 수 없다.
	 *
	 * @return 매도 시점 평단과 실현손익. 호출자가 {@code trade} 행에 기록한다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public SellResult applySell(Long accountId, String stockCode, long quantity, long price) {
		Holding holding = holdingRepository.findByAccountIdAndStockCode(accountId, stockCode)
			.orElseThrow(() -> new IllegalStateException(
				"보유하지 않은 종목을 매도했다. accountId=" + accountId + ", stockCode=" + stockCode));
		if (holding.getQuantity() < quantity) {
			throw new IllegalStateException("보유 수량보다 많이 매도했다. accountId=" + accountId + ", stockCode=" + stockCode
				+ ", 보유=" + holding.getQuantity() + ", 매도=" + quantity);
		}

		long avgBuyPriceAtSell = holding.getAvgBuyPrice();
		holding.sell(quantity, Instant.now());
		return new SellResult(avgBuyPriceAtSell, (price - avgBuyPriceAtSell) * quantity);
	}

	/**
	 * @param avgBuyPriceAtSell 매도 시점의 평균 매수가. 전량 매도로 보유 행의 평단이 0 이 되어도 이 값은 남는다 —
	 *                          {@code trade} 는 그때의 사실을 기록한다.
	 * @param realizedProfit    실현손익. 손실이면 음수다.
	 */
	public record SellResult(long avgBuyPriceAtSell, long realizedProfit) {
	}
}
