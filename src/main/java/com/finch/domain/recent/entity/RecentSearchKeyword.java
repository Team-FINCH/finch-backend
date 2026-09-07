package com.finch.domain.recent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * `recent_search_keyword` 테이블 (erd.md §2.11). 사용자당 검색어 하나이고 같은 검색어를 다시 치면 {@code searched_at} 만 바뀐다.
 * <p>
 * <b>검색어는 종목이 아니라 문자열이다.</b> 종목코드로 검색해도 그 문자열 그대로 저장하고, 응답에 시세 필드가 없는 것이 그 결정의
 * 표현이다 (apiSpec 6.2). {@code stock} 과 FK 로 묶지 않는 이유도 같다 — 검색 결과가 없는 검색어도 기록된다.
 * <p>
 * {@code id} 가 {@code DELETE /stocks/search/recent/{keywordId}} 의 그 값이다. 행 생성은 네이티브 UPSERT 다
 * ({@code RecentViewedStock} 과 같은 이유).
 */
@Entity
@Table(name = "recent_search_keyword")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecentSearchKeyword {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private Long userId;

	@Column(nullable = false, length = 50, updatable = false)
	private String keyword;

	@Column(nullable = false)
	private Instant searchedAt;
}
