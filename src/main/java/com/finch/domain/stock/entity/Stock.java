package com.finch.domain.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * `stock` 테이블 (erd.md §2.7) — 종목 마스터. 백엔드 DB 가 소유한다 (erd.md §1.1: KIS 프록시·AI DB 공유 기각).
 * <p>
 * 행은 {@code StockMasterSyncService} 가 KIS 마스터 파일로 만들고 고친다. 검색·상세·주문은 읽기만 한다.
 * <b>상장폐지는 행을 지우지 않고 {@code isActive=false}</b> 다 — trade·holding 이 FK 로 매달려 있어 지울 수 없고,
 * 검색은 활성만 보여주면 된다 (apiSpec 5.1, contracts C77).
 * <p>
 * {@code stockCode} 는 문자열이다. 선행 0 을 보존해야 하므로 정수형을 쓰지 않는다 ({@code 005930}). DB 는 {@code CHAR(6)} 이라
 * {@code @JdbcTypeCode(Types.CHAR)} 로 맞춘다 — {@code ddl-auto: validate} 는 JDBC 타입 코드로 비교해서, {@code columnDefinition="char(6)"}
 * 만 적으면 Hibernate 가 여전히 VARCHAR 로 기대해 "wrong column type [bpchar]" 로 기동이 실패한다.
 */
@Entity
@Table(name = "stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {

	@Id
	@JdbcTypeCode(Types.CHAR)
	@Column(name = "stock_code", length = 6, nullable = false, updatable = false)
	private String stockCode;

	@Column(nullable = false, length = 100)
	private String stockName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Market market;

	/** 거래정지. 화면은 뱃지를 띄우고 매수·매도를 막는다 (featureSpec 4장, contracts C46). */
	@Column(nullable = false)
	private boolean suspended;

	/** 거래정지 사유. 마스터 파일에는 사유 문자열이 없어 지금은 관리종목 여부 정도만 담긴다. */
	@Column(length = 200)
	private String suspendedReason;

	/**
	 * 전일 종가. 등락 계산의 기준이고 {@code daily_candle} 에서 유도할 수 있지만 매 요청 쓰이므로 캐시한다 —
	 * erd.md §2.7 이 "의도적 중복"이라고 적은 컬럼이다. 마스터 동기화가 KIS 기준가로 채우고, S10 일봉 배치가 다시 맞춘다.
	 */
	private Long previousClose;

	/** 상장폐지 시 false. 검색에서 빠진다. 마스터 파일에 없는 종목은 동기화가 false 로 내린다. */
	@Column(nullable = false)
	private boolean isActive;

	@Column(nullable = false)
	private Instant updatedAt;

	/** 마스터 동기화가 새 종목을 만들 때 부른다. */
	public static Stock of(String stockCode, String stockName, Market market, boolean suspended, String suspendedReason,
		Long previousClose, Instant now) {
		Stock stock = new Stock();
		stock.stockCode = stockCode;
		stock.stockName = stockName;
		stock.market = market;
		stock.suspended = suspended;
		stock.suspendedReason = suspendedReason;
		stock.previousClose = previousClose;
		stock.isActive = true;
		stock.updatedAt = now;
		return stock;
	}

	/**
	 * 마스터 행으로 갱신한다. 마스터에 다시 나타난 종목은 활성으로 돌아온다.
	 * {@code previousClose} 는 마스터가 값을 줄 때만 덮는다 — 0 이나 null 로 기존 값을 지우지 않는다.
	 */
	public void applyMaster(String stockName, Market market, boolean suspended, String suspendedReason,
		Long previousClose, Instant now) {
		this.stockName = stockName;
		this.market = market;
		this.suspended = suspended;
		this.suspendedReason = suspendedReason;
		if (previousClose != null && previousClose > 0) {
			this.previousClose = previousClose;
		}
		this.isActive = true;
		this.updatedAt = now;
	}

	/** 마스터 파일에서 사라진 종목. 상장폐지로 본다 (erd.md §2.7). */
	public void deactivate(Instant now) {
		this.isActive = false;
		this.updatedAt = now;
	}
}
