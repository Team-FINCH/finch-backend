package com.finch.domain.deposit.entity;

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

/**
 * `payment` 테이블 (erd.md §2.12). 충전의 <b>상태 머신</b>이다.
 * <p>
 * {@link Deposit} 과 나눈 이유 — deposit 은 원장 상세라 "행이 있으면 이미 돈이 움직였다"(불변식 6)여야 하는데,
 * 결제에는 "준비했지만 아직 승인 안 됨" 같은 중간 상태가 있다. 한 테이블로 합치면 원장 없는 deposit 행이 생겨
 * 불변식이 깨진다. 그래서 중간 상태는 전부 여기에 있고, deposit 은 DONE 이 된 것만 담는다.
 * <p>
 * <b>상태는 아래 메서드로만 바뀐다.</b> setter 가 없고, 각 메서드가 허용된 이전 상태를 검사한다.
 * 잘못된 전이는 {@link IllegalStateException} 이다 — 서비스가 상태를 먼저 판정해 사용자 에러로 바꾸므로
 * 여기 도달하면 서비스 쪽 판정이 빠진 것이고, 조용히 넘어가면 안 된다.
 * <p>
 * {@code payment_key} UNIQUE 가 충전 멱등성의 근거다 (apiSpec §1.4·§4.4). Redis 멱등성 필터를 쓰지 않는 이유는
 * 그 필터의 키가 클라이언트 UUID 인데, 결제창을 거쳐 돌아온 요청은 최초 호출과 다른 화면·다른 세션일 수 있어서다.
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

	/** apiSpec 4.2 의 {@code paymentId}. 카카오 {@code partner_order_id} 로도 이 값을 보낸다. */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** {@code Account} 엔티티가 아니라 식별자다. account 의 Entity 를 import 하지 않는다 (backConvention 2.4 규칙 3). */
	@Column(nullable = false, updatable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private PaymentMethod paymentMethod;

	/** 준비 시점 금액. confirm 이 보낸 금액과 다르면 위변조로 보고 FAILED 로 굳힌다 (apiSpec 4.4 판정 4). */
	@Column(nullable = false, updatable = false)
	private long amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PaymentStatus status;

	/** PG 가 발급한 키. 승인 전에는 null. 카카오는 승인 응답의 {@code aid}, 모의 이체는 {@code mock_pk_*}. */
	@Column(length = 64)
	private String paymentKey;

	/** PG 거래 식별자 (카카오 {@code tid}). ready 응답에서 받아 approve 때 되돌려 보낸다. 모의 이체는 null. */
	@Column(length = 64)
	private String pgTid;

	@Column(length = 50)
	private String failCode;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant approvedAt;

	/** 원장 반영 시각. deposit 행의 {@code created_at}·원장의 {@code occurred_at} 과 같은 값이다. */
	private Instant completedAt;

	/** 이후 READY 인 건은 FAILED(EXPIRED) 로 정리된다. 값은 {@code finch.deposit.ready-ttl} 이 정한다. */
	@Column(nullable = false, updatable = false)
	private Instant expiresAt;

	/** 결제 준비. {@code DepositService.ready} 만 부른다. */
	public static Payment ready(Long accountId, PaymentMethod paymentMethod, long amount, Instant expiresAt) {
		Payment payment = new Payment();
		payment.accountId = accountId;
		payment.paymentMethod = paymentMethod;
		payment.amount = amount;
		payment.status = PaymentStatus.READY;
		payment.expiresAt = expiresAt;
		return payment;
	}

	/** PG 의 ready 응답에서 받은 거래 식별자를 붙인다. 카카오만 쓴다. */
	public void attachPgTid(String pgTid) {
		requireStatus(PaymentStatus.READY, "attachPgTid");
		this.pgTid = pgTid;
	}

	/**
	 * PG 승인. READY 에서만 가능하다.
	 * <p>
	 * <b>여기서 돈은 움직이지 않는다.</b> 원장 반영은 {@link #complete} 로 가는 confirm 트랜잭션뿐이다.
	 * 그래서 이 전이를 일으키는 카카오 콜백이 무인증이어도 안전하다 (apiSpec §4.3.1).
	 */
	public void approve(String paymentKey, Instant approvedAt) {
		requireStatus(PaymentStatus.READY, "approve");
		this.paymentKey = paymentKey;
		this.approvedAt = approvedAt;
		this.status = PaymentStatus.APPROVED;
	}

	/** 원장 반영 완료. APPROVED 에서만 가능하다. 이후 같은 키의 confirm 은 재생이다. */
	public void complete(Instant completedAt) {
		requireStatus(PaymentStatus.APPROVED, "complete");
		this.completedAt = completedAt;
		this.status = PaymentStatus.DONE;
	}

	/**
	 * 실패로 굳힌다. READY·APPROVED 에서 가능하고 <b>DONE 에서는 불가능하다</b> — 이미 원장에 들어간 건은
	 * 되돌릴 수 없다 (충전 취소 없음, featureSpec 1.1). 이미 FAILED 면 사유를 덮지 않고 그대로 둔다.
	 */
	public void fail(String failCode) {
		if (status == PaymentStatus.FAILED) {
			return;
		}
		if (status == PaymentStatus.DONE) {
			throw new IllegalStateException("DONE 인 결제는 실패로 바꿀 수 없다 — 원장에 이미 반영됐다. paymentId=" + id);
		}
		this.failCode = failCode;
		this.status = PaymentStatus.FAILED;
	}

	public boolean isExpired(Instant now) {
		return status == PaymentStatus.READY && now.isAfter(expiresAt);
	}

	/** 요청이 보낸 키가 이 건의 키와 같은가. 승인 전(키 없음)이면 언제나 false 다. */
	public boolean matchesKey(String paymentKey) {
		return this.paymentKey != null && this.paymentKey.equals(paymentKey);
	}

	private void requireStatus(PaymentStatus expected, String action) {
		if (status != expected) {
			throw new IllegalStateException(
				action + " 는 " + expected + " 에서만 가능하다. 현재 " + status + ", paymentId=" + id);
		}
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}
}
