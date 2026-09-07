package com.finch.domain.recent.repository;

import com.finch.domain.recent.entity.RecentSearchKeyword;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** `recent_search_keyword` 는 recent 도메인 소유다. */
public interface RecentSearchKeywordRepository extends JpaRepository<RecentSearchKeyword, Long> {

	/** 기록. UPSERT 인 이유는 {@code RecentViewedStockRepository.upsert} 와 같다. 같은 검색어면 시각만 갱신한다 (apiSpec 6.2). */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(nativeQuery = true, value = """
		INSERT INTO recent_search_keyword (user_id, keyword, searched_at)
		VALUES (:userId, :keyword, :searchedAt)
		ON CONFLICT (user_id, keyword) DO UPDATE SET searched_at = EXCLUDED.searched_at
		""")
	void upsert(Long userId, String keyword, Instant searchedAt);

	/** 최대 10건 (apiSpec 6.2). 규칙은 최근 본 종목의 30건과 같다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(nativeQuery = true, value = """
		DELETE FROM recent_search_keyword
		 WHERE user_id = :userId
		   AND id NOT IN (SELECT id FROM recent_search_keyword
		                   WHERE user_id = :userId
		                   ORDER BY searched_at DESC, id DESC
		                   LIMIT :keep)
		""")
	int trimBeyond(Long userId, int keep);

	/** 목록. 조인이 없다 — 검색어는 종목이 아니라 문자열이라 붙일 것이 없다. */
	List<RecentSearchKeyword> findByUserIdOrderBySearchedAtDescIdDesc(Long userId);

	/**
	 * 개별 삭제. <b>{@code userId} 로 함께 좁힌다.</b> 남의 {@code keywordId} 를 지목해도 아무 일이 없고 응답은 똑같이 204 다 —
	 * 존재 여부 자체가 정보 노출이라 알려주지 않는다 (apiSpec 6.2, 이슈 #23).
	 */
	int deleteByIdAndUserId(Long id, Long userId);

	int deleteByUserId(Long userId);
}
