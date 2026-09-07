package com.finch.domain.stock.repository;

import com.finch.domain.stock.entity.Stock;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** `stock` 은 stock 도메인 소유다. 다른 도메인은 {@code StockService} 가 노출한 DTO 를 받는다 (backConvention 2.4 규칙 3). */
public interface StockRepository extends JpaRepository<Stock, String> {

	Optional<Stock> findByStockCodeAndIsActiveTrue(String stockCode);

	/**
	 * 검색 (apiSpec 5.1). 이름은 부분 일치, 코드는 접두 일치, 상장폐지 제외 (contracts C77).
	 * <p>
	 * 네이티브인 이유 — {@code ILIKE '%kw%'} 가 {@code ix_stock_name_trgm}(GIN, pg_trgm) 을 타게 하려면 Postgres 연산자를 그대로
	 * 써야 한다. JPQL 의 {@code lower(name) like} 는 B-tree 도 trigram 도 못 탄다 (erd.md §2.7).
	 * <p>
	 * 정렬: 코드 접두 일치 → 이름 접두 일치 → 나머지, 그 안에서 이름순. 자동완성이라 "삼성" 을 치면 "삼성전자" 가 "호텔삼성" 보다
	 * 위여야 한다. {@code LIMIT} 은 호출자가 검증한 1~10 이다.
	 */
	@Query(nativeQuery = true, value = """
		SELECT s.*
		  FROM stock s
		 WHERE s.is_active = true
		   AND (s.stock_name ILIKE '%' || :keyword || '%' OR s.stock_code LIKE :keyword || '%')
		 ORDER BY CASE WHEN s.stock_code LIKE :keyword || '%' THEN 0
		               WHEN s.stock_name ILIKE :keyword || '%' THEN 1
		               ELSE 2 END,
		          s.stock_name
		 LIMIT :size
		""")
	List<Stock> searchByKeyword(String keyword, int size);
}
