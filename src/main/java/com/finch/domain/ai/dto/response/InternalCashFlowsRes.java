package com.finch.domain.ai.dto.response;

import com.finch.domain.ledger.dto.response.CashFlowRes;
import com.finch.global.paging.CursorPage;
import java.util.List;

/**
 * `GET /internal/v1/cash-flows` (apiSpec 9.3). 목록 키가 {@code items} 가 아니라 <b>{@code cashFlows}</b> 다 — 9.2 가 {@code trades} 인
 * 것과 같은 규칙이다. 커서 규칙은 공개 API 와 같다.
 */
public record InternalCashFlowsRes(List<CashFlowRes> cashFlows, String nextCursor, boolean hasNext) {

	public static InternalCashFlowsRes from(CursorPage<CashFlowRes> page) {
		return new InternalCashFlowsRes(page.items(), page.nextCursor(), page.hasNext());
	}
}
