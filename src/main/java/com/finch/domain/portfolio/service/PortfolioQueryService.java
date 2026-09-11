package com.finch.domain.portfolio.service;

import com.finch.domain.account.dto.response.AccountBalanceRes;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.portfolio.dto.request.PortfolioSort;
import com.finch.domain.portfolio.dto.response.HeldStockRes;
import com.finch.domain.portfolio.dto.response.PortfolioRes;
import com.finch.domain.portfolio.repository.HoldingRepository;
import com.finch.domain.portfolio.service.HoldingValuationService.PricedHoldings;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보유 종목 목록 (`GET /portfolio`, apiSpec 8.1, featureSpec 9.2).
 * <p>
 * 예수금은 {@link AccountService} 에게 묻는다 — `account` 테이블은 account 도메인 소유이고 다른 도메인은 서비스를 거친다
 * (backConvention 2.4 규칙 3). portfolio(3층) → account(2층) 은 위에서 아래라 그대로 부를 수 있다.
 * <p>
 * <b>포트 구현이 이 클래스에 없는 이유</b>는 {@link HoldingValuationService} 주석에 있다 — 여기서 구현하면 account 와
 * 빈 순환이 된다.
 */
@Service
@RequiredArgsConstructor
public class PortfolioQueryService {

	/**
	 * 시세가 없는 종목은 뒤로 민다 ({@code nullsLast}). 값 없음을 0 으로 취급하면 실제로 0 인 종목과 섞여 화면에서 구분되지 않는다.
	 * 관심 목록의 {@code CHANGE_RATE} 정렬과 같은 판단이다.
	 */
	private static final Comparator<PortfolioRes.Holding> BY_EVALUATION =
		Comparator.comparing(PortfolioRes.Holding::evaluationAmount, Comparator.nullsLast(Comparator.reverseOrder()));
	private static final Comparator<PortfolioRes.Holding> BY_PROFIT_RATE =
		Comparator.comparing(PortfolioRes.Holding::evaluationProfitRate, Comparator.nullsLast(Comparator.reverseOrder()));

	private final AccountService accountService;
	private final HoldingValuationService holdingValuationService;
	private final HoldingRepository holdingRepository;

	/**
	 * 상단 요약(예수금·평가금액·총자산)과 보유 목록을 함께 돌려준다. 한 화면이 두 API 를 부르면 두 응답 사이에 시세가 바뀌어
	 * 합계와 항목이 어긋나 보인다 — 같은 평가 결과로 둘 다 만든다.
	 * <p>
	 * 정렬은 애플리케이션에서 한다 ({@link PortfolioSort}). 보유 종목 수는 한 사람이 가질 수 있는 만큼이라 메모리 정렬로 충분하다.
	 */
	@Transactional(readOnly = true)
	public PortfolioRes list(Long userId, PortfolioSort sort) {
		AccountBalanceRes balance = accountService.getBalance(userId);
		PricedHoldings priced = holdingValuationService.evaluate(balance.accountId());

		List<PortfolioRes.Holding> holdings =
			priced.sortedBy(sort == PortfolioSort.PROFIT_RATE ? BY_PROFIT_RATE : BY_EVALUATION);
		return PortfolioRes.of(balance.cashBalance(), priced.evaluationAmount(), priced.asOf(), holdings);
	}

	/**
	 * 보유 종목의 코드와 이름만 ({@code quantity > 0}). 알림함이 "논지 없는 보유 종목" 을 셀 때 쓴다 (apiSpec 6.4).
	 * <p>
	 * {@link #list} 를 쓰지 않는 이유 — 그쪽은 종목마다 시세 캐시를 읽고 평가금액을 계산한다. 알림함 뱃지는 홈·포트폴리오·내 정보
	 * 헤더에서 불리는데, 거기에 필요 없는 평가를 매번 얹을 이유가 없다.
	 */
	@Transactional(readOnly = true)
	public List<HeldStockRes> heldStocks(Long userId) {
		Long accountId = accountService.getBalance(userId).accountId();
		return holdingRepository.findHeld(accountId).stream()
			.map(row -> new HeldStockRes(row.getStockCode(), row.getStockName()))
			.toList();
	}
}
