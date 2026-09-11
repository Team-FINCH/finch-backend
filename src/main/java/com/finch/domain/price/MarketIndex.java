package com.finch.domain.price;

/**
 * 시장 지수 (apiSpec 5.7). 응답의 {@code indexCode} 가 이 이름 그대로이고, <b>선언 순서가 응답 순서다.</b>
 * <p>
 * 공급자별 코드(KIS 업종코드 {@code 0001}·{@code 1001})는 여기 두지 않는다 — 공급자를 모르는 조회 쪽까지 KIS 를 알게 된다.
 * 매핑은 {@code KisIndexFeed} 가 갖는다.
 */
public enum MarketIndex {
	KOSPI,
	KOSDAQ
}
