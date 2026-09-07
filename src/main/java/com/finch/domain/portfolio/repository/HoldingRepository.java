package com.finch.domain.portfolio.repository;

import com.finch.domain.portfolio.entity.Holding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** `holding` 은 portfolio 도메인 소유다. 다른 도메인은 포트나 서비스를 거친다 (backConvention 2.2·2.4). */
public interface HoldingRepository extends JpaRepository<Holding, Long> {

	/**
	 * 보유 목록 (apiSpec 8.1). <b>{@code quantity > 0} 만</b> — 전량 매도 후 남은 0 수량 행은 내부 구현이라 화면에 나가지 않는다
	 * (erd.md §2.6). {@code ix_holding_account_held} 가 그 조건의 부분 인덱스다.
	 * <p>
	 * 상장폐지 종목도 그대로 보여준다. 보유 중 상장폐지는 MVP 에서 생기지 않고(contracts C78), 생기더라도 가진 주식을 목록에서
	 * 지우면 사용자가 자기 자산을 볼 수 없다 — 검색·최근 본 종목과 판단이 다른 지점이다.
	 * <p>
	 * 정렬은 서비스가 한다. 평가금액·수익률 둘 다 현재가가 있어야 하는데 그 값은 Redis 에 있어 DB 가 볼 수 없다.
	 */
	@Query(nativeQuery = true, value = """
		SELECT h.stock_code AS "stockCode", s.stock_name AS "stockName",
		       h.quantity AS "quantity", h.avg_buy_price AS "avgBuyPrice"
		  FROM holding h
		  JOIN stock s ON s.stock_code = h.stock_code
		 WHERE h.account_id = :accountId
		   AND h.quantity > 0
		 ORDER BY h.id
		""")
	List<HoldingRow> findHeld(Long accountId);

	/**
	 * 체결 트랜잭션이 쓴다. 계좌 행이 이미 잠겨 있어(S1) 같은 계좌의 주문이 직렬화되므로 여기 추가 락을 걸지 않는다.
	 * <b>{@code quantity = 0} 인 행도 가져온다</b> — 재매수가 갱신할 대상이 바로 그 행이다.
	 */
	Optional<Holding> findByAccountIdAndStockCode(Long accountId, String stockCode);

	/**
	 * 종목 상세의 보유 카드 (apiSpec 5.2). {@code quantity > 0} 이 없으면 "보유 없음" 이다 — 포트 계약이 그렇게 정했다.
	 * <p>
	 * <b>{@code account} 를 조인해 {@code userId} 로 찾는다.</b> 계좌를 {@code AccountService} 에게 물어 {@code accountId} 를
	 * 얻는 것이 규칙 3 에 맞지만, 그러면 이 쿼리를 쓰는 {@code PortfolioQueryAdapter} 가 account 를 의존하게 되어
	 * {@code AccountService} → {@code ValuationPort} → 어댑터 → {@code AccountService} 로 빈 순환이 된다. 조회 전용 조인에
	 * DTO 프로젝션이라 규칙 4 의 예외에 든다 — 쓰지 않고, 엔티티를 가져오지 않고, 계좌 소유 규칙을 침범하지 않는다.
	 */
	@Query(nativeQuery = true, value = """
		SELECT h.quantity AS "quantity", h.avg_buy_price AS "avgBuyPrice"
		  FROM holding h
		  JOIN account a ON a.id = h.account_id
		 WHERE a.user_id = :userId
		   AND h.stock_code = :stockCode
		   AND h.quantity > 0
		""")
	Optional<HeldAmountRow> findHeldByUser(Long userId, String stockCode);

	/**
	 * 이 중 보유 중인 종목코드만 (관심 목록의 {@code held} 뱃지). 낱개로 묻지 않는 이유는 {@code HoldingQueryPort} 주석에 있다 —
	 * 관심 종목이 최대 50개라 그대로 N+1 이 된다. {@code account} 조인 이유는 {@link #findHeldByUser} 와 같다.
	 */
	@Query(nativeQuery = true, value = """
		SELECT h.stock_code
		  FROM holding h
		  JOIN account a ON a.id = h.account_id
		 WHERE a.user_id = :userId
		   AND h.quantity > 0
		   AND h.stock_code IN (:stockCodes)
		""")
	List<String> findHeldCodesAmongByUser(Long userId, List<String> stockCodes);
}
