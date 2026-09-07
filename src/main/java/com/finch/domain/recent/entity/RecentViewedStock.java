package com.finch.domain.recent.entity;

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
import org.hibernate.annotations.JdbcTypeCode;

/**
 * `recent_viewed_stock` 테이블 (erd.md §2.10). 사용자당 종목 하나이고 {@code uq_recent_viewed_user_stock} 가 그것을 보장한다.
 * <p>
 * <b>행을 JPA 로 만들지 않는다.</b> 기록은 네이티브 UPSERT 한 문장이다 ({@code RecentViewedStockRepository.upsert}) —
 * select-then-save 로 하면 같은 사용자가 두 탭에서 같은 종목을 동시에 열었을 때 UNIQUE 위반이 난다. 이 엔티티는 삭제 파생 쿼리와
 * 매핑 검증에만 쓰인다.
 * <p>
 * {@code user_id} 에 묶여 있고 계좌가 아니다 — 계좌를 건드려도 최근 본 종목은 남는다 (erd.md §7).
 */
@Entity
@Table(name = "recent_viewed_stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecentViewedStock {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private Long userId;

	/** {@code CHAR(6)} 매핑 이유는 {@code Stock.stockCode} 주석. */
	@JdbcTypeCode(Types.CHAR)
	@Column(name = "stock_code", length = 6, nullable = false, updatable = false)
	private String stockCode;

	@Column(nullable = false)
	private Instant viewedAt;
}
