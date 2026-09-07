package com.finch.domain.stock.master;

/** 마스터를 읽거나 파싱하지 못했다. 사용자 요청과 무관한 배치 오류라 {@code CustomException} 이 아니다 — 응답 형식으로 나갈 일이 없다. */
public class StockMasterLoadException extends RuntimeException {

	public StockMasterLoadException(String message) {
		super(message);
	}

	public StockMasterLoadException(String message, Throwable cause) {
		super(message, cause);
	}
}
