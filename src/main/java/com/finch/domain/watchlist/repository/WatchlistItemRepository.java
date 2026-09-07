package com.finch.domain.watchlist.repository;

import com.finch.domain.watchlist.entity.WatchlistItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** `watchlist_item` 은 watchlist 도메인 소유다. */
public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

	/**
	 * 정렬 두 가지가 DB 몫이다 (erd.md §2.9). 하나로 합치지 않는 이유는 S4 의 두 쿼리와 같다 — 정렬 조건을 파라미터로 넣으면
	 * 플래너가 인덱스를 고르지 못한다.
	 */
	String SELECT = """
		SELECT w.stock_code AS "stockCode", s.stock_name AS "stockName", w.created_at AS "registeredAt"
		  FROM watchlist_item w
		  JOIN stock s ON s.stock_code = w.stock_code
		 WHERE w.user_id = :userId
		""";

	/** {@code sort=REGISTERED}(기본). {@code ix_watchlist_user (user_id, created_at DESC)} 를 탄다. */
	@Query(nativeQuery = true, value = SELECT + " ORDER BY w.created_at DESC, w.id DESC")
	List<WatchlistRow> findRowsByRegistered(Long userId);

	/**
	 * {@code sort=NAME}. 조인한 종목명으로 DB 가 정렬한다.
	 * <p>
	 * <b>{@code COLLATE "ko-KR-x-icu"} 가 필요하다.</b> DB 기본 콜레이션이 {@code en_US.utf8} 인데, glibc 의 그 콜레이션은 한글을
	 * 가나다순으로 세우지 않는다 — 실제로 {@code 카카오 · 현대차 · 삼성전자} 순으로 나온다. 사용자가 "이름순"을 눌렀는데 가나다순이
	 * 아니면 고장으로 보인다. Postgres 가 함께 빌드하는 ICU 콜레이션은 {@code 삼성전자 · 카카오 · 현대차} 로 제대로 세운다.
	 * <p>
	 * DB 기본 콜레이션을 바꾸지 않고 이 쿼리에서만 지정하는 이유 — 기본을 바꾸려면 DB 를 다시 만들어야 하고, 한글 정렬이 필요한
	 * 자리는 지금 이 쿼리와 종목 검색 둘뿐이다.
	 */
	@Query(nativeQuery = true, value = SELECT + """
		 ORDER BY s.stock_name COLLATE "ko-KR-x-icu", w.id
		""")
	List<WatchlistRow> findRowsByName(Long userId);

	/**
	 * 중복 판정 (apiSpec 11.2 판정 순서: 종목 존재 → <b>중복</b> → 한도).
	 * <p>
	 * DB 의 UNIQUE 만으로도 중복은 막히지만 그때는 INSERT 시점이라 <b>한도 검사보다 뒤</b>가 된다. 이미 등록된 종목을 50개가 찬
	 * 상태에서 다시 담으면 {@code WATCHLIST_ALREADY_EXISTS} 여야 하는데 그러면 {@code LIMIT_EXCEEDED} 가 나간다.
	 * 그래서 순서를 위해 여기서 한 번 보고, 동시 요청 경합은 DB 제약이 막는다.
	 */
	boolean existsByUserIdAndStockCode(Long userId, String stockCode);

	long countByUserId(Long userId);

	/** 없는 대상을 해제해도 204 다 (apiSpec 11.2). 반환값은 로그용이 아니라 테스트용이다. */
	int deleteByUserIdAndStockCode(Long userId, String stockCode);
}
