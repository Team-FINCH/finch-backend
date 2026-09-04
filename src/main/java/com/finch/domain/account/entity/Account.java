package com.finch.domain.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * `account` 테이블 (erd.md §2.2). 사용자당 정확히 하나이고 `uq_account_user` 가 그것을 DB 에서 보장한다.
 * <p>
 * <b>여기 있는 두 숫자는 원장에서 파생된 스냅샷이다</b> (erd.md §1.3). 진실은 `ledger_entry` 이고,
 * 이 값들은 매번 {@code SUM} 하지 않으려고 물질화해 둔 것이다. 그래서 <b>같은 트랜잭션에서만</b>
 * 원장과 함께 갱신한다 — 따로 갱신하는 코드가 생기면 불변식 1·2 가 깨진다.
 * <ul>
 *   <li>{@code cashBalance} = {@code SUM(ledger_entry.cash_delta)} (불변식 1)</li>
 *   <li>{@code totalDepositedAmount} = {@code SUM(deposit.amount)} (불변식 2). <b>충전만</b> 더한다 —
 *       초기 지급은 충전이 아니므로 여기 들어가지 않는다.</li>
 * </ul>
 * {@code id} 는 API 응답에 나가지 않는다. 계좌는 사용자당 하나라 클라이언트가 지목할 대상이 아니고,
 * 모든 요청은 토큰의 사용자로 계좌를 찾는다 (apiSpec 1.6).
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * {@code User} 엔티티가 아니라 식별자를 그대로 든다. auth 의 Entity 를 import 하지 않기 위해서다
	 * (backConvention 2.4 규칙 3).
	 * <p>
	 * {@code unique = true} 는 문서 목적이다. {@code validate} 는 유니크 제약을 검사하지 않으므로
	 * 실제 방어선은 스키마의 {@code uq_account_user} 이고, 동시 가입 경합도 그 제약이 막는다.
	 */
	@Column(nullable = false, unique = true, updatable = false)
	private Long userId;

	/** 예수금. {@code ck_account_cash_balance} 가 음수를 DB 바닥에서 막는다. */
	@Column(nullable = false)
	private long cashBalance;

	/** 계좌 평생 누적 충전액. 충전 한도(1억)의 기준이다 (apiSpec 4.1). */
	@Column(nullable = false)
	private long totalDepositedAmount;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	/**
	 * 계좌 개설. {@code AccountService.openAccount} 만 부른다.
	 *
	 * @param initialCash 초기 예수금. 값은 {@code finch.account.initial-cash} 가 정한다 —
	 *                    엔티티가 정책 숫자를 들고 있으면 바꿀 때 코드를 고쳐야 한다.
	 */
	public static Account open(Long userId, long initialCash) {
		Account account = new Account();
		account.userId = userId;
		account.cashBalance = initialCash;
		account.totalDepositedAmount = 0L;
		return account;
	}

	/**
	 * 예수금을 갱신한다. <b>원장에 기록한 직후, 같은 트랜잭션에서만</b> 부른다.
	 * <p>
	 * 증감분이 아니라 <b>기록 직후 잔고를 그대로</b> 받는 이유 — 그 값은 이미 원장 행
	 * ({@code cash_balance_after})에 들어갔다. 여기서 다시 더하면 계산이 두 곳이 되고,
	 * 두 값이 갈라지는 순간 어느 쪽이 맞는지 알 수 없다. 원장이 진실이므로 그 값을 복사한다.
	 */
	public void applyBalance(long cashBalanceAfter) {
		this.cashBalance = cashBalanceAfter;
	}

	/**
	 * 누적 충전액을 늘린다. 충전 반영 트랜잭션(deposit)에서만 부른다.
	 * <p>
	 * 출금·매매는 이 값을 건드리지 않는다 — 정의가 "평생 누적 충전액"이고, 되돌리면 충전 한도를
	 * 무한히 우회할 수 있다 (erd.md §2.2).
	 */
	public void addDeposited(long amount) {
		this.totalDepositedAmount += amount;
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}
}
