package com.finch.domain.withdrawal.entity;

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
import org.hibernate.annotations.Immutable;

/**
 * `withdrawal` 테이블 (erd.md §2.13). <b>원장 상세</b>다 — {@code WITHDRAWAL} 원장 1행과 정확히 1:1 이다 (불변식 6).
 * <p>
 * <b>여기 행이 있으면 이미 돈이 빠졌다.</b> 충전의 {@code Payment} 같은 중간 상태가 없다 — 출금은 외부 PG 를 거치지
 * 않아 요청 한 번이 곧 확정이다. 그래서 이 엔티티는 출금 트랜잭션에서 원장 기록 직후에만 만들어지고, 원장과 마찬가지로
 * 바뀌지 않는다 ({@link Immutable}). 상태 컬럼도, 수단 컬럼도 없다 (featureSpec 3.4).
 * <p>
 * {@code amount} 는 <b>양수</b>다. 부호는 원장의 {@code cash_delta} 만 갖는다 — deposit 과 같은 규칙이라 내역 화면
 * (apiSpec 8.2)이 두 유형을 같은 방식으로 다룰 수 있다.
 */
@Entity
@Table(name = "withdrawal")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Withdrawal {

	/** apiSpec 4.5 의 {@code withdrawalId}. */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 짝이 되는 원장 행. 응답의 {@code cashBalanceAfter}·{@code withdrawnAt} 은 그 행이 들고 있다. */
	@Column(nullable = false, updatable = false)
	private Long ledgerEntryId;

	/** 조회·감사용 (erd.md §2.13). 원장을 거치지 않고 계좌별 합계를 낼 수 있다. */
	@Column(nullable = false, updatable = false)
	private Long accountId;

	/** 양수 절대값. {@code ck_withdrawal_amount} 가 0 이하를 DB 바닥에서 막는다. */
	@Column(nullable = false, updatable = false)
	private long amount;

	/** 원장의 {@code occurred_at} 과 같은 값을 넣는다 — 한 사건의 시각은 하나다. */
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	/** 출금 트랜잭션에서 원장 기록 직후에만 부른다. */
	public static Withdrawal of(Long ledgerEntryId, Long accountId, long amount, Instant createdAt) {
		Withdrawal withdrawal = new Withdrawal();
		withdrawal.ledgerEntryId = ledgerEntryId;
		withdrawal.accountId = accountId;
		withdrawal.amount = amount;
		withdrawal.createdAt = createdAt;
		return withdrawal;
	}
}
