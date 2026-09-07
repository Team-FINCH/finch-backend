package com.finch.domain.recent.repository;

import com.finch.domain.recent.entity.RecentViewedStock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** `recent_viewed_stock` 은 recent 도메인 소유다. */
public interface RecentViewedStockRepository extends JpaRepository<RecentViewedStock, Long> {

	/**
	 * 기록 (erd.md §2.10). <b>네이티브 UPSERT 한 문장이다.</b>
	 * <p>
	 * JPA 로 "있으면 갱신 없으면 저장"을 하면 조회와 저장 사이에 다른 요청이 끼어들어 {@code uq_recent_viewed_user_stock} 위반이
	 * 난다. 같은 사용자가 두 탭에서 같은 종목을 여는 것은 드문 일이 아니다. {@code ON CONFLICT DO UPDATE} 는 그 경합을 DB 가
	 * 한 문장 안에서 해결한다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(nativeQuery = true, value = """
		INSERT INTO recent_viewed_stock (user_id, stock_code, viewed_at)
		VALUES (:userId, :stockCode, :viewedAt)
		ON CONFLICT (user_id, stock_code) DO UPDATE SET viewed_at = EXCLUDED.viewed_at
		""")
	void upsert(Long userId, String stockCode, Instant viewedAt);

	/**
	 * FIFO 상한 유지 — 최신 {@code keep} 건만 남기고 지운다 (apiSpec 6.1, 최대 30건).
	 * <p>
	 * UPSERT 와 같은 트랜잭션에서 바로 뒤에 부른다. {@code viewed_at} 동률은 {@code id} 로 가른다 — 같은 밀리초에 두 종목을 봤을 때
	 * 어느 것이 잘릴지가 정해져 있지 않으면 목록이 요청마다 흔들린다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(nativeQuery = true, value = """
		DELETE FROM recent_viewed_stock
		 WHERE user_id = :userId
		   AND id NOT IN (SELECT id FROM recent_viewed_stock
		                   WHERE user_id = :userId
		                   ORDER BY viewed_at DESC, id DESC
		                   LIMIT :keep)
		""")
	int trimBeyond(Long userId, int keep);

	/** 목록 (apiSpec 6.1). 최신순이고 {@code ix_recent_viewed_user} 를 탄다. 상장폐지 종목은 제외한다 — 검색에서 빠진 것을 여기서 보여줄 이유가 없다. */
	@Query(nativeQuery = true, value = """
		SELECT r.stock_code AS "stockCode", s.stock_name AS "stockName", r.viewed_at AS "viewedAt"
		  FROM recent_viewed_stock r
		  JOIN stock s ON s.stock_code = r.stock_code
		 WHERE r.user_id = :userId
		   AND s.is_active = true
		 ORDER BY r.viewed_at DESC, r.id DESC
		""")
	List<RecentViewedRow> findRows(Long userId);

	/** 없는 대상을 지워도 예외가 아니다 — 반환값을 버리고 204 다 (apiSpec 11.2 멱등 규칙). */
	int deleteByUserIdAndStockCode(Long userId, String stockCode);

	int deleteByUserId(Long userId);
}
