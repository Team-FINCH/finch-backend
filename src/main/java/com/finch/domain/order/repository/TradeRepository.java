package com.finch.domain.order.repository;

import com.finch.domain.order.entity.Trade;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** `trade` 는 order 도메인 소유다. 다른 도메인은 이 리포지토리를 import 하지 않는다 (backConvention 2.4 규칙 3). */
public interface TradeRepository extends JpaRepository<Trade, Long> {

	/**
	 * 계좌의 체결을 최신순으로. 불변식 3·6 대조와 <b>S11 내부 API({@code GET /internal/v1/trades})의 커서 조회</b>가 쓴다 —
	 * {@code ix_trade_account_id_desc} 가 이 순서 그대로다. 화면용 매매 내역은 여기가 아니라 S4 의 원장 프로젝션이다.
	 */
	List<Trade> findByAccountIdOrderByIdDesc(Long accountId);

	/**
	 * 내부 API 의 커서 페이지 (apiSpec 9.2). "이 id 보다 작은 것" 을 최신순으로 {@code size + 1} 건 — {@code ix_trade_account_id_desc}
	 * 가 이 순서 그대로다. 첫 페이지는 호출자가 {@code Long.MAX_VALUE} 를 넘긴다 ({@code TransactionQueryService} 와 같은 규칙).
	 */
	List<Trade> findByAccountIdAndIdLessThanOrderByIdDesc(Long accountId, long id, Pageable pageable);

	/**
	 * 계좌의 종목별 <b>마지막 매수</b> 체결 (알림함 apiSpec 6.4 — "왜 담으셨나요?" 가 가리키는 매수). 종목마다 한 행이다.
	 * <p>
	 * PostgreSQL 의 {@code DISTINCT ON} 이라 네이티브 쿼리다. 종목별 {@code max(id)} 를 구한 뒤 다시 조인하는 이식 가능한 쿼리보다
	 * 한 번에 끝나고, {@code ix_trade_account_stock (account_id, stock_code, id DESC)} 가 이 정렬 그대로다.
	 */
	@Query(nativeQuery = true, value = """
		SELECT DISTINCT ON (t.stock_code)
		       t.stock_code AS "stockCode", t.id AS "tradeId", t.executed_at AS "executedAt"
		  FROM trade t
		 WHERE t.account_id = :accountId
		   AND t.side = 'BUY'
		 ORDER BY t.stock_code, t.id DESC
		""")
	List<LastBuyRow> findLastBuys(Long accountId);
}
