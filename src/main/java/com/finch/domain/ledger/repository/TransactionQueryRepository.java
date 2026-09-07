package com.finch.domain.ledger.repository;

import com.finch.domain.ledger.entity.LedgerEntry;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * `GET /transactions` 전용 읽기 쿼리 (apiSpec 8.2, erd.md §5). {@code ledger_entry} 를 기준으로 상세 테이블과
 * 종목명을 붙여 화면 한 행을 만든다.
 * <p>
 * <b>네이티브 SQL 인 이유</b> — ledger 는 1층(피참조 전용)이라 trade·deposit·withdrawal·stock 의 Entity 를 import
 * 할 수 없다 (backConvention 2.4 규칙 2·3). JPQL 은 엔티티가 있어야 조인할 수 있으므로 쓸 수 없고, 규칙 4 가 허용한
 * "조회 전용 조인 + DTO 프로젝션"은 테이블 이름으로 조인하는 네이티브 SQL 로만 성립한다. 결과는 {@link TransactionRow}
 * 인터페이스 프로젝션으로 받는다 — 엔티티를 만들지 않으니 영속성 컨텍스트에 아무것도 남지 않고 읽기 전용이 구조로 보장된다.
 * <p>
 * <b>계좌를 {@code account.user_id} 로 조인해서 찾는다.</b> 요청은 계좌 식별자를 받지 않고 토큰의 사용자로만 계좌를
 * 찾는데(apiSpec 1.6), 그것을 {@code AccountService} 에 물으면 ledger → account 역방향 참조가 된다. 같은 규칙 4 의
 * 조인으로 풀면 자바 의존은 생기지 않는다. {@code uq_account_user} 가 있어 사용자당 계좌 행은 하나다.
 * <p>
 * <b>쿼리가 둘인 이유</b> — {@code (:type IS NULL OR le.type = :type)} 한 문장으로 합치면 Postgres 가 null 파라미터의
 * 타입을 정하지 못해 실패하거나, 정하더라도 플래너가 {@code ix_ledger_account_type_id} 를 고르지 못한다. 필터가 있으면
 * {@code (account_id, type, id DESC)} 인덱스를, 없으면 {@code (account_id, id DESC)} 인덱스를 그대로 타게 조건을 나눈다.
 * <p>
 * 커서는 "이 id 보다 작은 것"이고 첫 페이지는 호출자가 {@code Long.MAX_VALUE} 를 넘긴다 — null 분기를 SQL 에 두지 않는다.
 * {@code LIMIT} 은 {@code size + 1} 이다 ({@code CursorPage.of}).
 */
public interface TransactionQueryRepository extends Repository<LedgerEntry, Long> {

	/**
	 * 별칭은 큰따옴표로 감싼다 — 안 그러면 Postgres 가 소문자로 접어 프로젝션 getter 와 맞지 않는다.
	 * {@code amount} 는 유형별 상세 테이블의 양수 절대값이고, 상세가 없는 {@code INITIAL_GRANT} 만 원장의 {@code cash_delta}
	 * 를 그대로 쓴다 (지급액이라 양수다). 부호는 원장이 갖고 화면은 유형으로 방향을 표시한다 (apiSpec 8.2).
	 */
	String SELECT = """
		SELECT le.id                                                    AS "id",
		       le.type                                                  AS "type",
		       le.occurred_at                                           AS "occurredAt",
		       t.stock_code                                             AS "stockCode",
		       s.stock_name                                             AS "stockName",
		       t.executed_price                                         AS "price",
		       t.quantity                                               AS "quantity",
		       t.avg_buy_price                                          AS "avgBuyPrice",
		       t.realized_profit                                        AS "realizedProfit",
		       COALESCE(t.executed_amount, d.amount, w.amount, ABS(le.cash_delta)) AS "amount",
		       d.payment_method                                         AS "paymentMethod"
		  FROM ledger_entry le
		  JOIN account a     ON a.id = le.account_id
		  LEFT JOIN trade t      ON t.ledger_entry_id = le.id
		  LEFT JOIN stock s      ON s.stock_code = t.stock_code
		  LEFT JOIN deposit d    ON d.ledger_entry_id = le.id
		  LEFT JOIN withdrawal w ON w.ledger_entry_id = le.id
		""";

	/** {@code type=ALL}. 원장 5종 전부. {@code ix_ledger_account_id_desc} 를 탄다. */
	@Query(nativeQuery = true, value = SELECT + """
		 WHERE a.user_id = :userId
		   AND le.id < :cursor
		 ORDER BY le.id DESC
		 LIMIT :limit
		""")
	List<TransactionRow> findPage(Long userId, long cursor, int limit);

	/** 유형 하나. {@code ix_ledger_account_type_id} 를 탄다. {@code type} 은 {@code LedgerType.name()} 이다. */
	@Query(nativeQuery = true, value = SELECT + """
		 WHERE a.user_id = :userId
		   AND le.type = :type
		   AND le.id < :cursor
		 ORDER BY le.id DESC
		 LIMIT :limit
		""")
	List<TransactionRow> findPageByType(Long userId, String type, long cursor, int limit);
}
