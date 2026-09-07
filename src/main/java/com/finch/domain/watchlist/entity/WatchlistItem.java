package com.finch.domain.watchlist.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * `watchlist_item` 테이블 (erd.md §2.9). 사용자당 종목 하나이고 {@code uq_watchlist_user_stock} 이 그것을 보장한다.
 * <p>
 * 불변이다 ({@link Immutable}) — 관심 종목은 등록과 해제뿐이고 고칠 필드가 없다. 순서 편집은 확장 범위다 (featureSpec 6장).
 * <p>
 * {@code user_id} 에 묶여 있고 계좌가 아니다 (erd.md §7).
 */
@Entity
@Table(name = "watchlist_item")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchlistItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private Long userId;

	/** {@code CHAR(6)} 매핑 이유는 {@code Stock.stockCode} 주석. */
	@JdbcTypeCode(Types.CHAR)
	@Column(name = "stock_code", length = 6, nullable = false, updatable = false)
	private String stockCode;

	/** 응답의 {@code registeredAt}. */
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	public static WatchlistItem of(Long userId, String stockCode, Instant createdAt) {
		WatchlistItem item = new WatchlistItem();
		item.userId = userId;
		item.stockCode = stockCode;
		item.createdAt = createdAt;
		return item;
	}
}
