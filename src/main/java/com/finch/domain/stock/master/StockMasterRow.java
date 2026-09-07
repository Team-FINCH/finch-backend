package com.finch.domain.stock.master;

import com.finch.domain.stock.entity.Market;

/**
 * 종목 마스터 한 행. KIS 파일이든 CSV 시드든 로더는 전부 이 모양으로 돌려주고, {@code StockMasterSyncService} 는 출처를 모른다.
 *
 * @param suspendedReason 거래정지 사유. 마스터 파일에는 사유 문자열이 없어 "거래정지"·"관리종목" 정도다. 정지가 아니면 null.
 * @param referencePrice  KIS 기준가(보통 전일 종가). {@code stock.previous_close} 의 초기값이 된다. 없으면 null.
 */
public record StockMasterRow(String stockCode, String stockName, Market market, boolean suspended, String suspendedReason,
	Long referencePrice) {
}
