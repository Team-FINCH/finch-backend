package com.finch.domain.deposit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * `deposit` 테이블 (erd.md §2.4). <b>원장 상세</b>다 — {@code DEPOSIT} 원장 1행과 정확히 1:1 이다 (불변식 6).
 * <p>
 * <b>여기 행이 있으면 이미 돈이 움직였다.</b> 준비·승인 같은 중간 상태는 {@link Payment} 가 갖는다.
 * 그래서 이 엔티티는 confirm 트랜잭션에서 원장 기록 직후에만 만들어지고, 원장과 마찬가지로 바뀌지 않는다
 * ({@link Immutable}). 충전 취소가 없으므로(featureSpec 1.1) 상태 컬럼도 없다.
 * <p>
 * {@code payment_id} UNIQUE 가 "한 결제로 두 번 충전되지 않는다"(불변식 7)를 DB 바닥에서 보장한다 —
 * 서비스의 FOR UPDATE 가 뚫려도 두 번째 INSERT 는 여기서 실패한다.
 */
@Entity
@Table(name = "deposit")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deposit {

	/** apiSpec 4.4 의 {@code depositId}. */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 짝이 되는 원장 행. 응답의 {@code cashBalanceAfter}·{@code depositedAt} 은 그 행이 들고 있다. */
	@Column(nullable = false, updatable = false)
	private Long ledgerEntryId;

	/** 한도 재계산·감사용 (erd.md §2.4). 불변식 2 의 {@code SUM(deposit.amount)} 가 이 컬럼으로 묶인다. */
	@Column(nullable = false, updatable = false)
	private Long accountId;

	@Column(nullable = false, updatable = false)
	private long amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private PaymentMethod paymentMethod;

	/** 어느 결제 건이 이 충전을 만들었나. confirm 재전송 시 이 값으로 최초 응답을 되찾는다. */
	@Column(nullable = false, updatable = false)
	private Long paymentId;

	/** 원장의 {@code occurred_at} 과 같은 값을 넣는다 — 한 사건의 시각은 하나다. */
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	/** confirm 트랜잭션에서 원장 기록 직후에만 부른다. */
	public static Deposit of(Long ledgerEntryId, Long accountId, long amount, PaymentMethod paymentMethod,
		Long paymentId, Instant createdAt) {
		Deposit deposit = new Deposit();
		deposit.ledgerEntryId = ledgerEntryId;
		deposit.accountId = accountId;
		deposit.amount = amount;
		deposit.paymentMethod = paymentMethod;
		deposit.paymentId = paymentId;
		deposit.createdAt = createdAt;
		return deposit;
	}
}
