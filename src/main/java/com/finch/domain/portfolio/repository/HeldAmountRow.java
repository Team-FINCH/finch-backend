package com.finch.domain.portfolio.repository;

/** 보유 수량과 평단만 필요한 곳에서 쓰는 읽기 전용 결과. {@code HoldingQueryPort.HoldingSnapshot} 으로 바뀌어 나간다. */
public interface HeldAmountRow {

	long getQuantity();

	long getAvgBuyPrice();
}
