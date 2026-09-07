package com.finch.domain.deposit.entity;

/**
 * 결제 수단 (apiSpec 4.2). 값 이름은 DB 의 {@code ck_payment_method}·{@code ck_deposit_method} CHECK 목록과
 * 문자 그대로 같아야 한다.
 */
public enum PaymentMethod {

	/** 카카오페이 실제 API. 테스트 CID 라 실제 금전 이동은 없지만 결제창·승인 흐름은 진짜다 (featureSpec 3.1). */
	KAKAOPAY,

	/** 계좌이체. 프론트의 자체 모의 화면이고 승인은 {@code POST /deposits/{id}/mock-approve} 가 한다. */
	TRANSFER
}
