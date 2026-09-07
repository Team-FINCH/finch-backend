package com.finch.domain.portfolio.port;

import com.finch.domain.account.port.ValuationPort;
import com.finch.domain.portfolio.service.HoldingValuationService;
import com.finch.domain.portfolio.service.HoldingValuationService.PricedHoldings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ValuationPort} 의 실제 구현. account(2층)가 선언하고 portfolio(3층)가 구현한다.
 * <b>{@code EmptyValuationPort} 를 이 MR 에서 지웠다</b> — 남겨 두면 빈이 둘이 되어 기동이 실패한다.
 * 이 MR 이 머지되면 `GET /account` 의 평가금액이 실제 값이 된다.
 * <p>
 * <b>{@link HoldingQueryAdapter} 와 한 클래스로 합치지 않는다.</b> 두 포트를 한 빈이 구현하면 테스트가
 * {@code @MockitoBean} 으로 한쪽을 목으로 바꿀 때 <b>그 빈이 통째로 대체되어 다른 포트의 주입이 깨진다</b> —
 * {@code BeanNotOfRequiredTypeException} 으로 관계없는 테스트의 컨텍스트가 뜨지 않는다. 포트 하나에 어댑터 하나가
 * {@code WatchlistQueryAdapter} 부터의 규칙이기도 하다.
 * <p>
 * <b>계좌를 읽지 않는다.</b> 읽으면 {@code AccountService} → {@code ValuationPort} → 이 빈 → {@code AccountService} 로
 * 빈 순환이 되어 기동이 깨진다. 계산은 {@code accountId} 만 받는 {@link HoldingValuationService} 가 한다.
 */
@Component
@RequiredArgsConstructor
public class ValuationAdapter implements ValuationPort {

	private final HoldingValuationService holdingValuationService;

	/** 보유 목록과 같은 엔진을 지나므로 계좌 요약의 평가금액과 잔고 화면의 합계가 어긋나지 않는다. */
	@Override
	public Valuation evaluate(Long accountId) {
		PricedHoldings priced = holdingValuationService.evaluate(accountId);
		return new Valuation(priced.evaluationAmount(), priced.asOf());
	}
}
