package com.finch.domain.deposit.entity;

/**
 * 결제 건의 상태 (erd.md §2.12). 전이는 {@code READY → APPROVED → DONE} 이고, 어느 단계에서든 {@code FAILED} 로
 * 갈 수 있으며 <b>{@code FAILED}·{@code DONE} 에서는 나가지 않는다.</b> 전이 규칙은 {@link Payment} 의 메서드가
 * 지킨다 — 상태를 직접 세팅하는 경로는 없다.
 */
public enum PaymentStatus {

	/** 결제 준비됨. 사용자는 결제창에 있거나 아직 가지 않았다. {@code expires_at} 뒤에는 FAILED 로 정리된다. */
	READY,

	/** PG 가 승인했다. <b>돈은 아직 움직이지 않았다</b> — 원장 반영은 confirm 뿐이다. */
	APPROVED,

	/** 원장에 반영됐다. 같은 paymentKey 로 다시 confirm 이 오면 최초 응답을 재생한다. */
	DONE,

	/** 실패로 굳었다. 사유는 {@code fail_code}. 다시 확정할 수 없고 처음부터 해야 한다 (featureSpec 3.3). */
	FAILED
}
