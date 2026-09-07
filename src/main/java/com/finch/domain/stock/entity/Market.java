package com.finch.domain.stock.entity;

/**
 * 상장 시장 (apiSpec 5.1 {@code market}). 값 이름은 DB {@code ck_stock_market} CHECK 목록과 문자 그대로 같아야 한다.
 * <p>
 * KIS 마스터 파일이 시장별로 따로 오므로({@code kospi_code.mst}·{@code kosdaq_code.mst}) 어느 파일에서 왔는지가 곧 이 값이다.
 * 코넥스·K-OTC 는 MVP 범위 밖이다 (featureSpec 4장 "코스피/코스닥").
 */
public enum Market {
	KOSPI,
	KOSDAQ
}
