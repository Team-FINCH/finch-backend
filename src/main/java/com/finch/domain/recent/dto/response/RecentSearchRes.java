package com.finch.domain.recent.dto.response;

import com.finch.domain.recent.entity.RecentSearchKeyword;
import com.finch.global.util.KstTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * `GET /stocks/search/recent` 응답 (apiSpec 6.2). 최신순, 최대 10건.
 *
 * @param items {@code keywordId} 는 {@code DELETE /stocks/search/recent/{keywordId}} 에 쓰는 값이다.
 *              시세 필드가 없는 것이 최근 본 종목(§6.1)과 다른 점이고 의도된 것이다 — 검색어는 종목이 아니라 문자열이다.
 */
public record RecentSearchRes(List<Item> items) {

	public record Item(Long keywordId, String keyword, OffsetDateTime searchedAt) {

		public static Item from(RecentSearchKeyword entity) {
			return new Item(entity.getId(), entity.getKeyword(), KstTime.toResponse(entity.getSearchedAt()));
		}
	}
}
