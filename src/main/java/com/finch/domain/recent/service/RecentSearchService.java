package com.finch.domain.recent.service;

import com.finch.domain.recent.dto.response.RecentSearchRes;
import com.finch.domain.recent.repository.RecentSearchKeywordRepository;
import com.finch.domain.stock.event.StockSearchedEvent;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최근 검색어 (apiSpec 6.2, featureSpec 4장). 최대 10건, 같은 검색어는 시각만 갱신.
 * <p>
 * 구조는 {@link RecentViewedService} 와 같다 — 검색이 발행하는 {@link StockSearchedEvent} 를 받아 기록하고 등록 API 가 없다.
 * 다른 점 하나: 검색어는 종목이 아니라 문자열이라 종목 테이블과 묶지 않고 시세도 붙이지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RecentSearchService {

	/** apiSpec 6.2 가 정한 상한. */
	static final int MAX_ITEMS = 10;

	/** `recent_search_keyword.keyword` 가 VARCHAR(50) 이다. 넘치면 INSERT 가 제약 위반으로 실패하므로 자른다. */
	private static final int MAX_KEYWORD_LENGTH = 50;

	private final RecentSearchKeywordRepository recentSearchKeywordRepository;

	/**
	 * {@code REQUIRES_NEW} 를 걷어낸 이유는 {@link RecentViewedService#on} 주석에 있다 (이슈 309 — 발행이 트랜잭션 밖으로
	 * 나가면서 요청당 커넥션 2개 점유가 사라졌다).
	 */
	@EventListener
	@Transactional
	public void on(StockSearchedEvent event) {
		String keyword = event.keyword();
		if (keyword.length() > MAX_KEYWORD_LENGTH) {
			keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
		}
		recentSearchKeywordRepository.upsert(event.userId(), keyword, Instant.now());
		recentSearchKeywordRepository.trimBeyond(event.userId(), MAX_ITEMS);
	}

	@Transactional(readOnly = true)
	public RecentSearchRes list(Long userId) {
		return new RecentSearchRes(recentSearchKeywordRepository.findByUserIdOrderBySearchedAtDescIdDesc(userId).stream()
			.map(RecentSearchRes.Item::from)
			.toList());
	}

	/** 남의 {@code keywordId} 를 지목해도 아무 일이 없고 응답은 204 다 (apiSpec 6.2). */
	@Transactional
	public void delete(Long userId, Long keywordId) {
		recentSearchKeywordRepository.deleteByIdAndUserId(keywordId, userId);
	}

	@Transactional
	public void deleteAll(Long userId) {
		recentSearchKeywordRepository.deleteByUserId(userId);
	}
}
