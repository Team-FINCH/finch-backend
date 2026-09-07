package com.finch.domain.ledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * `ledger_entry` 테이블 (erd.md §2.3). <b>잔고 변동의 단일 진실 공급원</b>이고,
 * {@code account.cash_balance} 와 {@code holding} 은 여기서 파생된 스냅샷이다 (erd.md §1.3).
 * <p>
 * <b>이 엔티티는 불변이다.</b> UPDATE·DELETE 를 하지 않고 정정은 반대 분개로 한다
 * (backConvention 6장). 그 규칙을 주석이 아니라 <b>코드로</b> 강제한 방법이 셋이다.
 * <ol>
 *   <li>{@link Immutable} — Hibernate 가 이 엔티티의 변경 감지를 아예 하지 않는다. 필드를 바꿔도
 *       UPDATE 문이 나가지 않는다. 조용히 무시되는 것이 위험해 보이지만, 애초에 바꿀 수단을 두지
 *       않았으므로(아래 2) 이건 마지막 그물이다.</li>
 *   <li>상태를 바꾸는 메서드가 없다. setter 도, 값을 고치는 도메인 메서드도 없다.
 *       {@link #record} 로 만드는 것이 전부다.</li>
 *   <li>{@code LedgerEntryRepository} 가 삭제 메서드를 노출하지 않는다.</li>
 * </ol>
 * 애플리케이션 규약만으로는 부족하므로 <b>DB 계정에서 이 테이블의 UPDATE·DELETE 권한을 회수하는 것</b>이
 * erd.md §2.3 의 최종 방어선이다. 그건 인프라 작업이라 이 MR 범위 밖이다.
 * <p>
 * 스키마 변경은 Flyway 전용이고 이 엔티티는 {@code ddl-auto: validate} 의 검사 대상일 뿐이다.
 */
@Entity
@Table(name = "ledger_entry")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

	/** apiSpec 8.2 의 {@code transactionId}. 커서 페이징의 커서도 이 값이다 (apiSpec 1.5). */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * {@code Account} 엔티티가 아니라 식별자를 그대로 든다.
	 * <p>
	 * 연관관계를 매핑하면 {@code ledger}(1층)가 {@code account}(2층)의 Entity 를 import 하게 되어
	 * 참조 방향이 뒤집힌다 (backConvention 2.4 규칙 2·3). 1층은 피참조 전용이다.
	 */
	@Column(nullable = false, updatable = false)
	private Long accountId;

	/**
	 * {@code EnumType.STRING} 이다. {@code ORDINAL} 을 쓰면 enum 상수 순서를 바꾸는 순간 이미 저장된
	 * 행의 의미가 통째로 달라지는데, 원장은 <b>고쳐 쓸 수 없는 시계열</b>이라 되돌릴 방법이 없다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16, updatable = false)
	private LedgerType type;

	/** 예수금 증감. 매수·출금은 음수, 나머지는 양수다 (erd.md §2.3). */
	@Column(nullable = false, updatable = false)
	private long cashDelta;

	/**
	 * 기록 직후 예수금. {@code SUM(cash_delta)} 로 매번 계산할 수도 있지만 그 값을 행에 박아 두면
	 * 내역 화면이 한 행만 읽어도 그 시점 잔고를 보여줄 수 있고, 불변식 1 의 대조 대상이 된다.
	 */
	@Column(nullable = false, updatable = false)
	private long cashBalanceAfter;

	/** 응답의 {@code occurredAt} (apiSpec 8.2). 사건이 일어난 시각이다. */
	@Column(nullable = false, updatable = false)
	private Instant occurredAt;

	/** 행이 만들어진 시각. {@code occurredAt} 과 보통 같지만 의미가 다르므로 따로 둔다. */
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	/**
	 * 원장 행을 만드는 <b>유일한 경로</b>다. {@code LedgerService} 만 이 메서드를 부른다
	 * (backConvention 2.5 — 원장 기록의 단일 경로).
	 * <p>
	 * {@code public} 인 것은 {@code LedgerService} 가 다른 패키지({@code ledger.service})에 있어서다.
	 * 자바의 package-private 은 하위 패키지까지 덮지 않는다. 실제 방어선은 접근 제어자가 아니라
	 * <b>{@code LedgerEntryRepository} 를 ledger 밖에서 부르지 않는다는 규칙</b>이고, 만들어 봐야
	 * 저장할 수단이 없으므로 이 메서드가 공개인 것은 위험을 늘리지 않는다.
	 */
	public static LedgerEntry record(Long accountId, LedgerType type, long cashDelta, long cashBalanceAfter,
		Instant occurredAt) {
		LedgerEntry entry = new LedgerEntry();
		entry.accountId = accountId;
		entry.type = type;
		entry.cashDelta = cashDelta;
		entry.cashBalanceAfter = cashBalanceAfter;
		entry.occurredAt = occurredAt;
		return entry;
	}

	/**
	 * {@code created_at} 은 NOT NULL 이고 DB 기본값이 없다. 채우는 책임이 애플리케이션에 있다.
	 * {@code User} 와 같은 방식이다 — JPA Auditing 도입은 전 도메인이 상속하는 결정이라 여기서 정하지 않는다.
	 */
	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}
}
